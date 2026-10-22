param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Reboot','Observe','Finish','Cleanup')][string]$Phase
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$stateFile = Join-Path $logRoot 'host-state.json'
$package = 'dev.smartgallery.validation'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.smartgallery.OsRecoveryTest'
function Save-State($value) { $value | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding utf8 }
function Read-Probe($state) {
    $result = & $adbPath -s $Serial shell run-as $package env "CLASSPATH=$($state.testPath)" app_process / dev.smartgallery.OsStateProbe $state.run 2>(Join-Path $logRoot 'probe-stderr.txt')
    if ($LASTEXITCODE -ne 0) { return $null }
    try { return ($result -join "`n") | ConvertFrom-Json } catch { return $null }
}
if ($Phase -eq 'Prepare') {
    if (Test-Path -LiteralPath $stateFile) { throw 'Use a fresh log directory for each run' }
    $testPath = ("$(& $adbPath -s $Serial shell pm path "$package.test")").Trim() -replace '^package:',''
    if ($LASTEXITCODE -ne 0 -or $testPath -notmatch '^/data/app/.+/base.apk$') { throw 'Install matching validation app and test APKs first' }
    $state = [pscustomobject]@{ run=[guid]::NewGuid().ToString(); serial=$Serial; testPath=$testPath; bootBefore=("$(& $adbPath -s $Serial shell cat /proc/sys/kernel/random/boot_id)").Trim(); checkpointBefore=-1; afterBootCheckpoint=-1; rebootRequested=$false; osAdvanced=$false }
    Save-State $state
    $prepare = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$testClass,'-e','osRun',$state.run,'-e','osPhase','prepare',$runner) -RedirectStandardOutput (Join-Path $logRoot 'prepare.txt') -RedirectStandardError (Join-Path $logRoot 'prepare-stderr.txt')
    $deadline = [DateTime]::UtcNow.AddSeconds(125)
    while ([DateTime]::UtcNow -lt $deadline) {
        $probe = Read-Probe $state
        if ($probe -and $probe.ready -and $probe.checkpoint -gt 0 -and $probe.checkpoint -lt $probe.planned) {
            'READY: production WorkManager reached an incomplete video checkpoint; no reboot performed yet.'
            $probe | ConvertTo-Json -Compress
            exit 0
        }
        if ($prepare.HasExited) { throw 'Preparation ended before an interruptible production checkpoint; inspect prepare.txt' }
        Start-Sleep -Milliseconds 500
    }
    throw 'Production worker did not reach a checkpoint; inspect logs and run Cleanup'
}
$state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
if ($state.serial -ne $Serial) { throw 'Run belongs to a different device' }
if ($Phase -eq 'Reboot') {
    if ($state.rebootRequested) { throw 'Reboot was already requested for this run' }
    $probe = Read-Probe $state
    if (-not $probe -or -not $probe.ready -or $probe.checkpoint -ge $probe.planned) { throw 'Refusing reboot without a live incomplete owned fixture checkpoint' }
    $state.checkpointBefore = $probe.checkpoint
    $state.rebootRequested = $true
    Save-State $state
    & $adbPath -s $Serial reboot
    if ($LASTEXITCODE -ne 0) { throw 'ADB reboot failed; no OS recovery claim' }
    'Reboot requested. Unlock the phone normally if needed, then run Observe. No app launch is performed by this helper.'
} elseif ($Phase -eq 'Observe') {
    if (-not $state.rebootRequested) { throw 'Reboot phase has not run' }
    if (-not $state.PSObject.Properties['afterBootCheckpoint']) {
        $state | Add-Member -NotePropertyName afterBootCheckpoint -NotePropertyValue -1
        $state.osAdvanced = $false
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        $boot = ("$(& $adbPath -s $Serial shell cat /proc/sys/kernel/random/boot_id 2>$null)").Trim()
        if ($boot -and $boot -ne $state.bootBefore) {
            $probe = Read-Probe $state
            if ($probe -and $probe.ready -and $state.afterBootCheckpoint -lt 0) {
                # Shutdown can commit frames after the last pre-reboot probe. Require progress
                # between two post-boot reads as well; retained shutdown writes are insufficient.
                $state.afterBootCheckpoint = $probe.checkpoint
                Save-State $state
            } elseif ($probe -and $probe.ready -and $probe.checkpoint -gt [Math]::Max($state.checkpointBefore, $state.afterBootCheckpoint)) {
                $state.osAdvanced = $true
                Save-State $state
                'PASS: changed boot ID and checkpoint advanced between post-boot probes before any host activity/instrumentation launch.'
                $probe | ConvertTo-Json -Compress
                exit 0
            }
        }
        Start-Sleep -Seconds 3
    }
    throw 'No OS-only advancement observed yet. Unlock normally if required; do not launch Smart Gallery before repeating Observe. No recovery pass claimed.'
} elseif ($Phase -in @('Finish','Cleanup')) {
    try {
        if ($Phase -eq 'Finish') {
            if (-not $state.osAdvanced) { throw 'Observe must first prove OS-only checkpoint advancement' }
            & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e osRun $state.run -e osPhase verify $runner > (Join-Path $logRoot 'verify.txt') 2>&1
            $result = Get-Content -LiteralPath (Join-Path $logRoot 'verify.txt') -Raw
            if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[24]') { throw 'OS recovery assertions failed; inspect verify.txt' }
            $result -split "`r?`n" | Where-Object { $_ -match 'OS recovery assertion:|OK \(' }
        }
    } finally {
        & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e osRun $state.run -e osPhase cleanup $runner > (Join-Path $logRoot 'cleanup.txt') 2>&1
        if ((Get-Content -LiteralPath (Join-Path $logRoot 'cleanup.txt') -Raw) -notmatch 'OK \(1 test\)') { throw 'Owned fixture cleanup failed; inspect cleanup.txt' }
    }
}

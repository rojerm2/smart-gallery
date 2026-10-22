param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [Parameter(Mandatory=$true)][ValidateSet('charging','thermal')][string]$Mode,
    [switch]$StaleChargingStatus
)
$ErrorActionPreference = 'Stop'
if ($StaleChargingStatus -and $Mode -ne 'charging') { throw 'Stale charging status applies only to charging mode' }
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$run = [guid]::NewGuid().ToString()
$package = 'dev.smartgallery.validation'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.smartgallery.ResourceConstraintTest'
if ([int]("$(& $adbPath -s $Serial shell getprop ro.build.version.sdk)").Trim() -lt 29) { throw 'Resource host requires Android 10+' }
$baselineBattery = (& $adbPath -s $Serial shell dumpsys battery) -join "`n"
$baselineThermal = (& $adbPath -s $Serial shell dumpsys thermalservice) -join "`n"
if ($baselineBattery -match 'UPDATES STOPPED' -or $baselineThermal -match 'IsStatusOverride:\s*true') { throw 'Existing sensor override detected; refusing to replace it' }
if ($baselineBattery -notmatch 'USB powered: true') { throw 'Keep the physical test phone charging over USB' }
$baselineBattery | Set-Content (Join-Path $logRoot 'battery-before.txt')
$baselineThermal -split "`n" | Select-String 'IsStatusOverride|Thermal Status' | Set-Content (Join-Path $logRoot 'thermal-before.txt')
[pscustomobject]@{ run=$run; serial=$Serial; mode=$Mode; package=$package; staleChargingStatus=[bool]$StaleChargingStatus } | ConvertTo-Json | Set-Content (Join-Path $logRoot 'host-state.json')
$testProcess = $null
function Restore-Sensor {
    if ($Mode -eq 'charging') { & $adbPath -s $Serial shell dumpsys battery reset }
    else { & $adbPath -s $Serial shell cmd thermalservice reset }
    if ($LASTEXITCODE -ne 0) { throw 'Sensor restoration failed; restore manually before proceeding' }
}
try {
    if ($Mode -eq 'charging') {
        & $adbPath -s $Serial shell dumpsys battery unplug
        if ($LASTEXITCODE -ne 0) { throw 'Simulated unplug was rejected' }
        # On the tested HyperOS, unplug alone retains status=CHARGING. Supply consistent
        # DISCHARGING status by default; the explicit stale-status regression retains CHARGING.
        $reportedStatus = if ($StaleChargingStatus) { 2 } else { 3 }
        & $adbPath -s $Serial shell dumpsys battery set status $reportedStatus
    }
    else { & $adbPath -s $Serial shell cmd thermalservice override-status 3 }
    if ($LASTEXITCODE -ne 0) { throw 'Controlled sensor signal was rejected' }
    $output = Join-Path $logRoot 'instrumentation.txt'
    $testProcess = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$testClass,'-e','resourceRun',$run,'-e','resourceMode',$Mode,$runner) -RedirectStandardOutput $output -RedirectStandardError (Join-Path $logRoot 'instrumentation-stderr.txt')
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $blocked = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $marker = & $adbPath -s $Serial exec-out run-as $package cat "no_backup/resource-$run.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $blocked = (($marker -join "`n") | ConvertFrom-Json).blocked } catch { $blocked = $false }
            if ($blocked) { break }
        }
        if ($testProcess.HasExited) { throw 'Resource test ended before proving deferral; inspect instrumentation.txt' }
        Start-Sleep -Milliseconds 300
    }
    if (-not $blocked) { throw 'No controlled deferral observed' }
    Restore-Sensor
    $completionDeadline = [DateTime]::UtcNow.AddSeconds(260)
    while (-not $testProcess.HasExited -and [DateTime]::UtcNow -lt $completionDeadline) { Start-Sleep -Milliseconds 300 }
    if (-not $testProcess.HasExited) { throw 'No completion after restoring physical sensors' }
    $testProcess.WaitForExit()
    $result = Get-Content -LiteralPath $output -Raw
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[24]') { throw 'Resource recovery assertions failed; inspect instrumentation.txt' }
    $result -split "`r?`n" | Where-Object { $_ -match 'Resource constraint |OK \(' }
} finally {
    Restore-Sensor
    if ($testProcess -and -not $testProcess.HasExited) {
        & $adbPath -s $Serial shell am force-stop $package
        if (-not $testProcess.WaitForExit(5000)) { $testProcess.Kill() }
    }
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e resourceRun $run -e resourceMode cleanup $runner > (Join-Path $logRoot 'cleanup.txt') 2>&1
    if ((Get-Content -LiteralPath (Join-Path $logRoot 'cleanup.txt') -Raw) -notmatch 'OK \(1 test\)') { throw 'Exact owned-fixture cleanup failed' }
    $afterBattery = (& $adbPath -s $Serial shell dumpsys battery) -join "`n"
    $afterThermal = (& $adbPath -s $Serial shell dumpsys thermalservice) -join "`n"
    if ($afterBattery -match 'UPDATES STOPPED' -or $afterThermal -match 'IsStatusOverride:\s*true' -or $afterBattery -notmatch 'USB powered: true') { throw 'Physical sensor restoration is not confirmed' }
    $afterBattery | Set-Content (Join-Path $logRoot 'battery-after.txt')
    $afterThermal -split "`n" | Select-String 'IsStatusOverride|Thermal Status' | Set-Content (Join-Path $logRoot 'thermal-after.txt')
    'RESTORED: physical USB charging and non-overridden thermal reporting verified.'
}

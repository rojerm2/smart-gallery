param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$TestApk,
    [Parameter(Mandatory=$true)][string]$LogDirectory
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$testPath = (Resolve-Path -LiteralPath $TestApk).Path
if (Test-Path -LiteralPath $LogDirectory) { throw 'Use a new evidence directory' }
$package = 'dev.smartgallery.validation'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.smartgallery.LongVideoIntegrationTest'
$run = [guid]::NewGuid().ToString()
& $adbPath -s $Serial shell run-as $package true
if ($LASTEXITCODE -ne 0) { throw 'Install the matching disposable validation app first' }
$api = [int]("$(& $adbPath -s $Serial shell getprop ro.build.version.sdk)").Trim()
if ($api -lt 29) { throw 'This fixture host requires Android 10+' }
$permissionState = (& $adbPath -s $Serial shell dumpsys package $package) -join "`n"
$permissions = if ($api -ge 34) { @('READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_VISUAL_USER_SELECTED') } elseif ($api -ge 33) { @('READ_MEDIA_IMAGES','READ_MEDIA_VIDEO') } else { @('READ_EXTERNAL_STORAGE') }
foreach ($permission in $permissions) {
    if ($permissionState -notmatch ([regex]::Escape("android.permission.$permission") + ': granted=false')) { throw 'Validation shared-gallery access must be denied; personal permissions are not changed' }
}
$battery = (& $adbPath -s $Serial shell dumpsys battery) -join "`n"
$thermal = (& $adbPath -s $Serial shell dumpsys thermalservice) -join "`n"
$storage = ((& $adbPath -s $Serial shell dumpsys devicestoragemonitor) | Select-String -Pattern '\blevel=|\bmSeq=') -join "`n"
if ($battery -match 'UPDATES STOPPED' -or $thermal -match 'IsStatusOverride:\s*true' -or $storage -notmatch 'mForceState=UNKNOWN\b' -or $storage -match '\blevel=(LOW|FULL)\b') { throw 'Existing resource override or low storage detected; refusing this long-video observation' }
if ($battery -notmatch 'USB powered: true') { throw 'Keep the physical phone connected over charging USB' }
New-Item -ItemType Directory -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
[pscustomobject]@{run=$run;serial=$Serial;package=$package} | ConvertTo-Json | Set-Content (Join-Path $logRoot 'host-state.json')
& $adbPath -s $Serial install -r $testPath
if ($LASTEXITCODE -ne 0) { throw 'Matching validation test runner installation failed' }
$process = $null
try {
    $process = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$testClass,'-e','longVideoRun',$run,'-e','longVideoPhase','run',$runner) -RedirectStandardOutput (Join-Path $logRoot 'instrumentation.txt') -RedirectStandardError (Join-Path $logRoot 'instrumentation-stderr.txt')
    while (-not $process.HasExited) {
        $appPidText = ("$(& $adbPath -s $Serial shell pidof $package)").Trim()
        if ($appPidText -match '^\d+$') {
            $channel = ("$(& $adbPath -s $Serial shell run-as $package cat "/proc/$appPidText/wchan" 2>$null)").Trim()
            if ($channel -in @('do_freezer_trap','__refrigerator')) {
                "Foregrounding frozen live long-video process $appPidText; no restart."
                & $adbPath -s $Serial shell am start -n "$package/dev.smartgallery.MainActivity"
            }
        }
        Start-Sleep -Milliseconds 800
    }
    $process.WaitForExit()
    $result = Get-Content -LiteralPath (Join-Path $logRoot 'instrumentation.txt') -Raw
    if ($result -notmatch 'OK \(1 test\)' -or $result -notmatch 'Long-video complete:.*checkpoints=600/600' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[234]') { throw 'Long-video acceptance did not pass; inspect the actual native result' }
    $result -split "`r?`n" | Where-Object { $_ -match 'Long-video complete:|OK \(' }
} finally {
    if ($process -and -not $process.HasExited) {
        # Exception cleanup only; observation latency never causes a process restart.
        & $adbPath -s $Serial shell am force-stop $package
        if (-not $process.WaitForExit(5000)) { $process.Kill() }
    }
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e longVideoRun $run -e longVideoPhase cleanup $runner > (Join-Path $logRoot 'cleanup.txt') 2>&1
    $cleanup = Get-Content -LiteralPath (Join-Path $logRoot 'cleanup.txt') -Raw
    if ($cleanup -notmatch 'OK \(1 test\)' -or $cleanup -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[234]') { throw 'Owned long-video fixture cleanup was not confirmed' }
    & $adbPath -s $Serial shell am start -n dev.smartgallery/dev.smartgallery.MainActivity
}

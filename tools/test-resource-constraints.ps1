param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [Parameter(Mandatory=$true)][ValidateSet('charging','thermal','storage')][string]$Mode,
    [switch]$StaleChargingStatus
)
$ErrorActionPreference = 'Stop'
if ($StaleChargingStatus -and $Mode -ne 'charging') { throw 'Stale charging status applies only to charging mode' }
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
if (Test-Path -LiteralPath $LogDirectory) { throw 'Use a new evidence directory; existing logs are preserved' }
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$run = [guid]::NewGuid().ToString()
$package = 'dev.smartgallery.validation'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.smartgallery.ResourceConstraintTest'
if ([int]("$(& $adbPath -s $Serial shell getprop ro.build.version.sdk)").Trim() -lt 29) { throw 'Resource host requires Android 10+' }
$baselineBattery = (& $adbPath -s $Serial shell dumpsys battery) -join "`n"
$baselineThermal = (& $adbPath -s $Serial shell dumpsys thermalservice) -join "`n"
function Read-StorageSignal {
    $dump = & $adbPath -s $Serial shell dumpsys devicestoragemonitor
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the OS storage signal' }
    # Fixed states and byte counts only; volume identifiers never enter evidence logs.
    ($dump | Select-String -Pattern '\blevel=|\bmSeq=') -join "`n"
}
if ($Mode -eq 'storage') {
    $baselineStorage = Read-StorageSignal
    if ($baselineStorage -notmatch 'mForceState=UNKNOWN\b' -or $baselineStorage -notmatch '\blevel=NORMAL\b' -or $baselineStorage -match '\blevel=(LOW|FULL)\b') { throw 'Storage is already forced, low, or unreadable; refusing to replace its state' }
    $baselineStorage | Set-Content (Join-Path $logRoot 'storage-before.txt')
}
if ($baselineBattery -match 'UPDATES STOPPED' -or $baselineThermal -match 'IsStatusOverride:\s*true') { throw 'Existing sensor override detected; refusing to replace it' }
if ($baselineBattery -notmatch 'USB powered: true') { throw 'Keep the physical test phone charging over USB' }
$baselineBattery | Set-Content (Join-Path $logRoot 'battery-before.txt')
$baselineThermal -split "`n" | Select-String 'IsStatusOverride|Thermal Status' | Set-Content (Join-Path $logRoot 'thermal-before.txt')
[pscustomobject]@{ run=$run; serial=$Serial; mode=$Mode; package=$package; staleChargingStatus=[bool]$StaleChargingStatus } | ConvertTo-Json | Set-Content (Join-Path $logRoot 'host-state.json')
$testProcess = $null
function Restore-Sensor {
    if ($Mode -eq 'charging') { & $adbPath -s $Serial shell dumpsys battery reset }
    elseif ($Mode -eq 'thermal') { & $adbPath -s $Serial shell cmd thermalservice reset }
    else { & $adbPath -s $Serial shell cmd devicestoragemonitor reset -f }
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
    elseif ($Mode -eq 'thermal') { & $adbPath -s $Serial shell cmd thermalservice override-status 3 }
    else { & $adbPath -s $Serial shell cmd devicestoragemonitor force-low -f }
    if ($LASTEXITCODE -ne 0) { throw 'Controlled sensor signal was rejected' }
    if ($Mode -eq 'storage') {
        $signalDeadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $forcedStorage = Read-StorageSignal
            if ($forcedStorage -match 'mForceState=LOW\b' -and $forcedStorage -match '\blevel=LOW\b') { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $signalDeadline)
        if ($forcedStorage -notmatch 'mForceState=LOW\b' -or $forcedStorage -notmatch '\blevel=LOW\b') { throw 'Controlled low-storage state was not observed' }
        $forcedStorage | Set-Content (Join-Path $logRoot 'storage-controlled.txt')
    }
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
    if ($Mode -eq 'storage') {
        $restorationDeadline = [DateTime]::UtcNow.AddSeconds(10)
        do {
            $afterStorage = Read-StorageSignal
            if ($afterStorage -match 'mForceState=UNKNOWN\b' -and $afterStorage -match '\blevel=NORMAL\b' -and $afterStorage -notmatch '\blevel=(LOW|FULL)\b') { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $restorationDeadline)
        $afterStorage | Set-Content (Join-Path $logRoot 'storage-after.txt')
        if ($afterStorage -notmatch 'mForceState=UNKNOWN\b' -or $afterStorage -notmatch '\blevel=NORMAL\b' -or $afterStorage -match '\blevel=(LOW|FULL)\b') { throw 'Unforced normal storage reporting is not confirmed' }
    }
    $afterBattery | Set-Content (Join-Path $logRoot 'battery-after.txt')
    $afterThermal -split "`n" | Select-String 'IsStatusOverride|Thermal Status' | Set-Content (Join-Path $logRoot 'thermal-after.txt')
    'RESTORED: physical USB charging and non-overridden thermal reporting verified.'
    if ($Mode -eq 'storage') { 'RESTORED: unforced normal OS storage reporting verified.' }
    & $adbPath -s $Serial shell am start -n dev.smartgallery/dev.smartgallery.MainActivity
}

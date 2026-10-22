param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$TestApk,
    [Parameter(Mandatory=$true)][string]$LogDirectory
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$testPath = (Resolve-Path -LiteralPath $TestApk).Path
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$run = [guid]::NewGuid().ToString()
$testClass = 'dev.smartgallery.ProcessRecoveryTest'
$runner = 'dev.smartgallery.test/androidx.test.runner.AndroidJUnitRunner'
& $adbPath -s $Serial shell run-as dev.smartgallery true
if ($LASTEXITCODE -ne 0) { throw 'Install the matching debuggable Smart Gallery app before running this check' }
& $adbPath -s $Serial install -r $testPath
if ($LASTEXITCODE -ne 0) { throw 'Could not install test runner' }
$prepareLog = Join-Path $logRoot 'prepare.txt'
$prepareError = Join-Path $logRoot 'prepare-stderr.txt'
$prepareProcess = $null
try {
    $prepareProcess = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$testClass,'-e','recoveryRun',$run,'-e','recoveryPhase','prepare',$runner) -RedirectStandardOutput $prepareLog -RedirectStandardError $prepareError
    $deadline = [DateTime]::UtcNow.AddSeconds(100)
    $ready = $null
    while ([DateTime]::UtcNow -lt $deadline) {
        $marker = & $adbPath -s $Serial exec-out run-as dev.smartgallery cat "no_backup/recovery-$run/ready.json" 2>$null
        if ($LASTEXITCODE -eq 0) {
            try { $ready = ($marker -join "`n") | ConvertFrom-Json } catch { $ready = $null }
            if ($ready) { break }
        }
        if ($prepareProcess.HasExited) { throw "Preparation exited before a real checkpoint. See $prepareLog" }
        Start-Sleep -Milliseconds 300
    }
    if (-not $ready) { throw "No interruptible checkpoint. See $prepareLog" }
    $appProcessId = [int]$ready.pid
    $actualProcessId = (& $adbPath -s $Serial shell pidof dev.smartgallery).Trim()
    if ($actualProcessId -ne "$appProcessId") { throw 'Refusing to signal a process that is not the current Smart Gallery process' }
    & $adbPath -s $Serial shell run-as dev.smartgallery touch "no_backup/recovery-$run/interrupt.request"
    if ($LASTEXITCODE -ne 0) { throw 'App process interruption could not be requested' }
    if (-not $prepareProcess.WaitForExit(10000)) { throw 'Interrupted instrumentation did not exit' }
    $preparation = Get-Content -LiteralPath $prepareLog -Raw
    if ($preparation -notmatch 'Test requests SIGKILL at checkpoint' -or $preparation -notmatch 'Process crashed' -or $preparation -match 'INSTRUMENTATION_STATUS_CODE: -2') { throw 'Preparation did not reach the explicit self-SIGKILL path' }
    $resumeLog = Join-Path $logRoot 'resume.txt'
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e recoveryRun $run -e recoveryPhase resume $runner > $resumeLog 2>&1
    $result = Get-Content -LiteralPath $resumeLog -Raw
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[24]') { throw "Recovery assertions failed. See $resumeLog" }
    $result -split "`r?`n" | Where-Object { $_ -match 'Actual process recovery:|OK \(' }
    'PASS: actual app SIGKILL, new-process checkpoint resume, durable corrections and unchanged fixture.'
} finally {
    if ($prepareProcess -and -not $prepareProcess.HasExited) {
        & $adbPath -s $Serial shell am force-stop dev.smartgallery
        if (-not $prepareProcess.WaitForExit(5000)) { $prepareProcess.Kill() }
    }
    # Explicit cleanup is idempotent after resume; it targets only this generated run UUID/owned URI.
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e recoveryRun $run -e recoveryPhase cleanup $runner > (Join-Path $logRoot 'cleanup.txt') 2>&1
    if ((Get-Content -LiteralPath (Join-Path $logRoot 'cleanup.txt') -Raw) -notmatch 'OK \(1 test\)') { throw 'Fixture cleanup failed; inspect cleanup.txt before continuing' }
}

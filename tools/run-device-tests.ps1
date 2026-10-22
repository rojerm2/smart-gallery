param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [ValidateSet('dev.smartgallery','dev.smartgallery.validation')][string]$Package = 'dev.smartgallery'
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$output = Join-Path $logRoot 'instrumentation.txt'
$errors = Join-Path $logRoot 'instrumentation-stderr.txt'
& $adbPath -s $Serial shell run-as $Package true
if ($LASTEXITCODE -ne 0) { throw 'Install the matching debug app and instrumentation APKs first' }
$run = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r',"$Package.test/androidx.test.runner.AndroidJUnitRunner") -RedirectStandardOutput $output -RedirectStandardError $errors
try {
    while (-not $run.HasExited) {
        $appPidText = ("$(& $adbPath -s $Serial shell pidof $Package)").Trim()
        if ($appPidText -match '^\d+$') {
            $waitChannel = ("$(& $adbPath -s $Serial shell run-as $Package cat "/proc/$appPidText/wchan" 2>$null)").Trim()
            if ($waitChannel -eq '__refrigerator') {
                # Observed on OPPO: thaw the existing process; do not restart a live test on timeout.
                "Foregrounding frozen live test process $appPidText; existing instrumentation preserved."
                & $adbPath -s $Serial shell am start -n "$Package/dev.smartgallery.MainActivity"
            }
        }
        Start-Sleep -Milliseconds 800
    }
    $run.WaitForExit()
    $result = Get-Content -LiteralPath $output -Raw
    if ($result -notmatch 'OK \(\d+ tests?\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2') {
        throw "Device suite failed or did not finish normally. Inspect $output and $errors"
    }
    [pscustomobject]@{
        Passed = ([regex]::Matches($result,'(?m)^INSTRUMENTATION_STATUS_CODE: 0\r?$')).Count
        Skipped = ([regex]::Matches($result,'(?m)^INSTRUMENTATION_STATUS_CODE: -[34]\r?$')).Count
        Log = $output
    }
} finally {
    # No timeout-triggered app restart, reboot, permission grant or data reset.
    $run.Dispose()
}

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
if ($Package -eq 'dev.smartgallery.validation') {
    $apiLevel = [int]("$(& $adbPath -s $Serial shell getprop ro.build.version.sdk)").Trim()
    $fixturePermissions = if ($apiLevel -ge 34) { @('READ_MEDIA_IMAGES','READ_MEDIA_VIDEO','READ_MEDIA_VISUAL_USER_SELECTED') } elseif ($apiLevel -ge 33) { @('READ_MEDIA_IMAGES','READ_MEDIA_VIDEO') } else { @('READ_EXTERNAL_STORAGE') }
    $permissionState = (& $adbPath -s $Serial shell dumpsys package $Package) -join "`n"
    foreach ($permission in $fixturePermissions) {
        if ($permissionState -notmatch ([regex]::Escape("android.permission.$permission") + ': granted=false')) { throw 'The validation suite requires denied shared-gallery access before starting' }
    }
}
$testArguments = @('-s',$Serial,'shell','am','instrument','-w','-r')
if ($Package -eq 'dev.smartgallery.validation') { $testArguments += @('-e','listener','dev.smartgallery.ActiveScreenListener') }
$testArguments += "$Package.test/androidx.test.runner.AndroidJUnitRunner"
$run = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList $testArguments -RedirectStandardOutput $output -RedirectStandardError $errors
try {
    while (-not $run.HasExited) {
        $appPidText = ("$(& $adbPath -s $Serial shell pidof $Package)").Trim()
        if ($appPidText -match '^\d+$') {
            $waitChannel = ("$(& $adbPath -s $Serial shell run-as $Package cat "/proc/$appPidText/wchan" 2>$null)").Trim()
            if ($waitChannel -in @('__refrigerator','do_freezer_trap')) {
                # Observed vendor freezing on OPPO/Redmi: foreground the same live process.
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
    if ($Package -eq 'dev.smartgallery.validation' -and $result -match 'Fixture tests require no shared-gallery permission') { throw 'Gallery access changed during the validation suite; fixture skips are not a full pass' }
    [pscustomobject]@{
        Passed = ([regex]::Matches($result,'(?m)^INSTRUMENTATION_STATUS_CODE: 0\r?$')).Count
        Skipped = ([regex]::Matches($result,'(?m)^INSTRUMENTATION_STATUS_CODE: -[34]\r?$')).Count
        Log = $output
    }
} finally {
    # No timeout-triggered app restart, reboot, permission grant or data reset.
    $run.Dispose()
}

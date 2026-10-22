param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [switch]$ManualSelection
)
$ErrorActionPreference = 'Stop'
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$package = 'dev.smartgallery.validation'
$permissions = @('android.permission.READ_MEDIA_IMAGES','android.permission.READ_MEDIA_VIDEO','android.permission.READ_MEDIA_VISUAL_USER_SELECTED')
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.smartgallery.PermissionLifecycleTest'
if ([int]("$(& $adbPath -s $Serial shell getprop ro.build.version.sdk)").Trim() -lt 34) { throw 'Selected-media lifecycle requires Android 14+' }
$baselinePermissions = (& $adbPath -s $Serial shell dumpsys package $package) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Unable to read validation permission baseline' }
foreach ($permission in $permissions) {
    if ($baselinePermissions -notmatch ([regex]::Escape($permission) + ': granted=false')) { throw 'Start with only the disposable validation app denied gallery access; personal grants must not be altered' }
}
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$run = [guid]::NewGuid().ToString()
[pscustomobject]@{run=$run;serial=$Serial;package=$package} | ConvertTo-Json | Set-Content (Join-Path $logRoot 'host-state.json')
function Revoke-ValidationAccess {
    foreach ($permission in $permissions) {
        & $adbPath -s $Serial shell pm revoke $package $permission
        if ($LASTEXITCODE -ne 0) { throw 'Validation-only permission revocation failed' }
        & $adbPath -s $Serial shell pm clear-permission-flags $package $permission user-set user-fixed
        if ($LASTEXITCODE -ne 0) { throw 'Validation-only permission prompt reset failed' }
    }
}
function Invoke-Phase([string]$Phase) {
    $output = Join-Path $logRoot "$Phase.txt"
    $manual = if ($ManualSelection) { 'true' } else { 'false' }
    $test = Start-Process -FilePath $adbPath -WindowStyle Hidden -PassThru -ArgumentList @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',$testClass,'-e','permissionRun',$run,'-e','permissionMode',$Phase,'-e','manualPermissionSelection',$manual,'-e','listener','dev.smartgallery.ActiveScreenListener','-e','screenHoldMs','420000',$runner) -RedirectStandardOutput $output -RedirectStandardError (Join-Path $logRoot "$Phase-stderr.txt")
    try {
        $phaseSeconds = if ($ManualSelection -and $Phase -in @('selected','reselected')) { 380 } else { 180 }
        $deadline = [DateTime]::UtcNow.AddSeconds($phaseSeconds)
        while (-not $test.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 400 }
        if (-not $test.HasExited) { throw "Permission phase $Phase exceeded its bounded observation" }
        $test.WaitForExit()
        $result = Get-Content -LiteralPath $output -Raw
        if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[24]') { throw "Permission phase $Phase failed; inspect its aggregate log" }
        $result -split "`r?`n" | Where-Object { $_ -match 'Permission lifecycle |OK \(' }
    } finally {
        if (-not $test.HasExited) {
            & $adbPath -s $Serial shell am force-stop $package
            if (-not $test.WaitForExit(5000)) { $test.Kill() }
        }
        $test.Dispose()
    }
}
try {
    Invoke-Phase 'prepare'
    Revoke-ValidationAccess
    Invoke-Phase 'denied'
    Revoke-ValidationAccess
    Invoke-Phase 'full'
    Revoke-ValidationAccess
    Invoke-Phase 'selected'
    Invoke-Phase 'reselected'
    Revoke-ValidationAccess
    Invoke-Phase 'revoked'
} finally {
    # Only this fixed disposable UID is changed. Fixture deletion runs under its separate owner.
    Revoke-ValidationAccess
    Invoke-Phase 'cleanup'
    # A pending system picker may finish while cleanup closes its calling activity.
    # Revoke again after that UI lifecycle has ended and verify the actual final state.
    & $adbPath -s $Serial shell am force-stop $package
    Revoke-ValidationAccess
    $finalPermissions = (& $adbPath -s $Serial shell dumpsys package $package) -join "`n"
    foreach ($permission in $permissions) {
        if ($finalPermissions -notmatch ([regex]::Escape($permission) + ': granted=false')) { throw 'Final validation-only denied access was not restored' }
    }
}

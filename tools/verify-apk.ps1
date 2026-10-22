param([Parameter(Mandatory=$true)][string]$Apk,[Parameter(Mandatory=$true)][string]$Aapt2)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$resolvedApk = (Resolve-Path -LiteralPath $Apk).Path
$permissions = & $Aapt2 dump permissions $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK permissions' }
if ($permissions -match 'android.permission.INTERNET|android.permission.ACCESS_NETWORK_STATE|MANAGE_EXTERNAL_STORAGE') { throw 'Unexpected network or all-files permission' }
$archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
try {
    $paths = @($archive.Entries | ForEach-Object FullName)
    foreach ($required in @('assets/models/mobile_facenet.tflite','assets/mlkit_label_default_model/mobile_ica_8bit_with_metadata_tflite','assets/models_bundled/blazeface.tfl','assets/notices/MODELS.md','assets/notices/APACHE-2.0.txt')) {
        if ($required -notin $paths) { throw "Missing bundled resource: $required" }
    }
    if (-not ($paths -match '^assets/mlkit-google-ocr-models/.*conv_model')) { throw 'Missing bundled OCR resources' }
    if ($paths -match '^assets/(astronaut|grace_hopper|chelsea|coffee|later-appearance|evaluation/)') { throw 'Test or private evaluation assets entered app APK' }
    $stream = $archive.GetEntry('assets/models/mobile_facenet.tflite').Open()
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $digest = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
    } finally { $stream.Dispose() }
    if ($digest -ne '2046ab776fba2ec7e420767ffe7eb74a7402a8412d3d7ea8b9f4ca8420e5f3d7') { throw 'Bundled recognition checksum mismatch' }
} finally { $archive.Dispose() }
Write-Output 'PASS: all bundled models and notices present; recognition checksum matches; no network/all-files permission or test media.'
Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256

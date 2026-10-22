param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory=$true)][ValidatePattern('^Pictures/[A-Za-z0-9_. /-]+/$')][string]$Folder,
    [Parameter(Mandatory=$true)][string]$LogDirectory,
    [ValidateRange(1,20)][int]$SampleLimit=5,
    [ValidateRange(0,1000000)][int]$SampleOffset=0
)
$ErrorActionPreference = 'Stop'
if ($Folder.Contains('..')) { throw 'A scoped Pictures subfolder is required' }
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
$logRoot = (Resolve-Path -LiteralPath $LogDirectory).Path
$run = [guid]::NewGuid().ToString()
$testClass = 'dev.smartgallery.PrivateFolderEvaluationTest'
$runner = 'dev.smartgallery.test/androidx.test.runner.AndroidJUnitRunner'
[pscustomobject]@{run=$run;serial=$Serial;sampleLimit=$SampleLimit;sampleOffset=$SampleOffset} | ConvertTo-Json | Set-Content (Join-Path $logRoot 'host-state.json')
try {
    $output = Join-Path $logRoot 'instrumentation.txt'
    # Folder is validated to contain no shell quotation/control characters.
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e folderEvalRun $run -e privateFolder "'$Folder'" -e sampleLimit $SampleLimit -e sampleOffset $SampleOffset $runner > $output 2>&1
    $result = Get-Content -LiteralPath $output -Raw
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -[24]') { throw 'Folder evaluation did not complete; inspect aggregate log' }
    $result -split "`r?`n" | Where-Object { $_ -match 'Consented folder aggregates:|Counts have no|OK \(' }
} finally {
    # Restores only this run's saved pause preference after a failed/crashed test as well.
    & $adbPath -s $Serial shell am instrument -w -r -e class $testClass -e folderEvalRun $run -e folderEvalCleanup true $runner > (Join-Path $logRoot 'cleanup.txt') 2>&1
    if ((Get-Content -LiteralPath (Join-Path $logRoot 'cleanup.txt') -Raw) -notmatch 'OK \(1 test\)') { throw 'Original pause-state restoration is unconfirmed; inspect cleanup.txt' }
}

param([string]$EvidenceDirectory='docs/qa/ui-restoration/ui2/accepted')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\project-env.ps1"
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if(-not $evidence.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must stay in Memento'}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
@{passed=$false;status='running';scope='UI2 targeted classes, not full release suite'} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
$classes=@('PlanSummaryInstrumentedTest','StudyLayoutInstrumentedTest','HomeNavigationInstrumentedTest','StudyUiInstrumentedTest','MementoTimeUiInstrumentedTest','MementoUiInstrumentedTest','CatalogInstrumentedTest','LegacyImportUiInstrumentedTest','ExtensionUiInstrumentedTest')
$results=@()
foreach($test in $classes) {
    $relative=Join-Path $EvidenceDirectory $test
    & "$PSScriptRoot\verify-device.ps1" -EvidenceDirectory $relative -TestClass "com.scott.memento.$test"
    $log=Get-Content -LiteralPath (Join-Path $projectRoot "$relative\instrumentation.log") -Raw
    $match=[regex]::Match($log,'OK \((\d+) tests?\)')
    if(-not $match.Success){throw "Missing successful result for $test"}
    $results+=@{class=$test;tests=[int]$match.Groups[1].Value;passed=$true;log="$relative\instrumentation.log"}
}
@{passed=$true;scope='UI2 targeted classes, separate runner invocations, not the complete suite';classes=$results;
  tests=($results|ForEach-Object{$_.tests}|Measure-Object -Sum).Sum;
  apk_sha256=(Get-FileHash -Algorithm SHA256 "$projectRoot\app\build\outputs\apk\debug\app-debug.apk").Hash.ToLowerInvariant()} |
  ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8

param([string]$EvidenceDirectory='docs/qa/ui-restoration/ui5/accepted')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\project-env.ps1"
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if(-not $evidence.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must stay in Memento'}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
@{passed=$false;status='running';scope='UI5 targeted classes, not the complete release suite'} | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
$classes=@('LegacyImportUiInstrumentedTest','SettingsLayoutInstrumentedTest','LibraryLayoutInstrumentedTest','LibraryQueryInstrumentedTest','DetailLayoutInstrumentedTest','ExtensionUiInstrumentedTest','CatalogInstrumentedTest','HomeNavigationInstrumentedTest','StudyLayoutInstrumentedTest','StudyUiInstrumentedTest','MementoUiInstrumentedTest','MementoTimeUiInstrumentedTest')
$applicationApk=Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk=Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$results=@()
foreach($test in $classes) {
    $applicationBefore=(Get-FileHash -Algorithm SHA256 -LiteralPath $applicationApk).Hash.ToLowerInvariant()
    $testBefore=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()
    $relative=Join-Path $EvidenceDirectory $test
    & "$PSScriptRoot\verify-device.ps1" -EvidenceDirectory $relative -TestClass "com.scott.memento.$test"
    $applicationAfter=(Get-FileHash -Algorithm SHA256 -LiteralPath $applicationApk).Hash.ToLowerInvariant()
    $testAfter=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()
    if($applicationBefore -ne $applicationAfter -or $testBefore -ne $testAfter){throw "APK changed during $test"}
    $log=Get-Content -LiteralPath (Join-Path $projectRoot "$relative\instrumentation.log") -Raw
    $match=[regex]::Match($log,'OK \((\d+) tests?\)')
    if(-not $match.Success){throw "Missing successful result for $test"}
    $results+=@{class=$test;tests=[int]$match.Groups[1].Value;passed=$true;log="$relative\instrumentation.log";
        application_apk_sha256=$applicationBefore;application_apk_sha256_after=$applicationAfter;
        test_apk_sha256=$testBefore;test_apk_sha256_after=$testAfter}
}
@{passed=$true;scope='UI5 targeted classes, separate runner invocations, not the complete release suite';classes=$results;
  tests=($results|ForEach-Object{$_.tests}|Measure-Object -Sum).Sum;
  apk_sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $applicationApk).Hash.ToLowerInvariant();
  test_apk_sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()} |
  ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8

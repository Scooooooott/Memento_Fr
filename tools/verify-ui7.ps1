param([string]$EvidenceDirectory='docs/qa/ui-restoration/ui7/full-suite')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\project-env.ps1"
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
$ui7Root=[System.IO.Path]::GetFullPath((Join-Path $projectRoot 'docs\qa\ui-restoration\ui7'))
if(-not $evidence.StartsWith($ui7Root+'\',[System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'UI7 full-suite evidence must stay inside docs/qa/ui-restoration/ui7'
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$started=[DateTime]::UtcNow.ToString('o')
@{stage='UI7';passed=$false;status='running';started_at_utc=$started;
    scope='One complete ordinary Android runner; ProcessRecovery runs separately'} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
$applicationApk=Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$testApk=Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
$applicationBefore=(Get-FileHash -Algorithm SHA256 -LiteralPath $applicationApk).Hash.ToLowerInvariant()
$testBefore=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()
$python='E:\Programs\Python\Python312\python.exe'
$collector=Join-Path $PSScriptRoot 'verify_ui7_full_suite.py'
& $python -B $collector --evidence-directory $EvidenceDirectory --prepare
if($LASTEXITCODE -ne 0){throw 'Could not freeze the complete Android source method set'}
# A failed installation or preflight must not accidentally reuse a previous successful log.
$log=Join-Path $evidence 'instrumentation.log'
if(Test-Path -LiteralPath $log){Remove-Item -LiteralPath $log}
$runnerCompleted=$false
$runnerError=''
try {
    # No TestClass: verify-device explicitly excludes only the host-driven ProcessRecovery class.
    & "$PSScriptRoot\verify-device.ps1" -EvidenceDirectory $EvidenceDirectory
    $runnerCompleted=$true
} catch {
    $runnerError=$_.Exception.Message
}
$applicationAfter=(Get-FileHash -Algorithm SHA256 -LiteralPath $applicationApk).Hash.ToLowerInvariant()
$testAfter=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()
$arguments=@('-B',$collector,'--evidence-directory',$EvidenceDirectory,'--started-at',$started,
    '--application-before',$applicationBefore,'--application-after',$applicationAfter,
    '--test-before',$testBefore,'--test-after',$testAfter)
if($runnerCompleted){$arguments+='--runner-completed'}
if($runnerError){$arguments+=@('--runner-error',$runnerError)}
& $python @arguments
if($LASTEXITCODE -ne 0){throw "UI7 complete Android suite did not pass. $runnerError"}

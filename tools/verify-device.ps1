param([string]$Serial = 'emulator-5556', [switch]$SkipBuild, [string]$EvidenceDirectory = 'docs/qa/latest')
. "$PSScriptRoot\project-env.ps1"
$evidencePath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if (-not $evidencePath.StartsWith($projectRoot.TrimEnd('\') + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Evidence must stay inside the project'
}
New-Item -ItemType Directory -Force -Path $evidencePath | Out-Null
if (-not $SkipBuild) {
    & "$PSScriptRoot\build.ps1" -Tasks @('assembleDebug', 'testDebugUnitTest', 'assembleDebugAndroidTest', 'lintDebug')
}
Push-Location $projectRoot
try {
    & adb -s $Serial install -r 'app/build/outputs/apk/debug/app-debug.apk'
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed' }
    & adb -s $Serial install -r 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed' }
    $result = & adb -s $Serial shell am instrument -w -r 'com.scott.frenchvocab.test/androidx.test.runner.AndroidJUnitRunner' 2>&1
    $result | Set-Content -LiteralPath (Join-Path $evidencePath 'instrumentation.log') -Encoding utf8
    $result | Select-Object -Last 20
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests\)') {
        throw "Android instrumentation did not pass. See $evidencePath\instrumentation.log"
    }
    & adb -s $Serial pull '/sdcard/Android/data/com.scott.frenchvocab/files/qa/.' (Join-Path $evidencePath 'screenshots')
    if ($LASTEXITCODE -ne 0) { throw 'Could not retrieve screenshots' }
} finally { Pop-Location }

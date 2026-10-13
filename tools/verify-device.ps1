param([string]$EvidenceDirectory='docs/qa/p8/accepted',[string]$TestClass='')
. "$PSScriptRoot\project-env.ps1"
$evidence = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if (-not $evidence.StartsWith($projectRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must stay in Memento' }
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$serial = 'emulator-5562'
$name = (& adb -s $serial emu avd name) -join "`n"
if ($LASTEXITCODE -ne 0 -or ($name.Split("`n") | ForEach-Object { $_.Trim() }) -notcontains 'MementoApi35') { throw 'Tests require the dedicated MementoApi35 emulator' }
$qemu = (& adb -s $serial shell getprop ro.kernel.qemu) -join ''
if ($qemu.Trim() -ne '1') { throw 'Refusing a physical device' }
& adb -s $serial install -r "$projectRoot\app\build\outputs\apk\debug\app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'Application installation failed' }
& adb -s $serial install -r "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw 'Test APK installation failed' }
$instrumentArgs=@('shell','am','instrument','-w','-r')
if($TestClass) {
    if($TestClass -notmatch '^com\.scott\.memento\.[A-Za-z]+(#[A-Za-z]+)?$') { throw 'Invalid Memento test filter' }
    $instrumentArgs+=@('-e','class',$TestClass)
} else {
    # This class requires two host-orchestrated invocations with a real process stop between them.
    $instrumentArgs+=@('-e','notClass','com.scott.memento.ProcessRecoveryInstrumentedTest')
}
$instrumentArgs+='com.scott.memento.test/androidx.test.runner.AndroidJUnitRunner'
$result = & adb -s $serial @instrumentArgs 2>&1
$result | Set-Content -LiteralPath (Join-Path $evidence 'instrumentation.log') -Encoding utf8
$result | Select-Object -Last 25
if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Instrumentation did not pass' }
& adb -s $serial pull '/sdcard/Android/data/com.scott.memento/files/qa/.' (Join-Path $evidence 'screenshots')
if ($LASTEXITCODE -ne 0) { throw 'Could not retrieve screenshots' }

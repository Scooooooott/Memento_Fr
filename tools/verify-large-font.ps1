. "$PSScriptRoot\project-env.ps1"
$serial='emulator-5562'
$avd=(& adb -s $serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or ($avd.Split("`n") | ForEach-Object {$_.Trim()}) -notcontains 'MementoApi35'){throw 'Font QA requires MementoApi35'}
if(((& adb -s $serial shell getprop ro.kernel.qemu) -join '').Trim() -ne '1'){throw 'Refusing a physical device'}
$previous=((& adb -s $serial shell settings get system font_scale) -join '').Trim()
if($previous -ne 'null' -and $previous -notmatch '^\d+(\.\d+)?$'){throw 'Unexpected previous font scale'}
$passed=$false
try {
    & adb -s $serial shell settings put system font_scale 1.5
    if($LASTEXITCODE -ne 0){throw 'Could not set font scale'}
    & "$PSScriptRoot\verify-device.ps1" -EvidenceDirectory 'docs/qa/p8/large-font' -TestClass 'com.scott.memento.StudyUiInstrumentedTest'
    $passed=$true
} finally {
    if($previous -eq 'null'){& adb -s $serial shell settings delete system font_scale | Out-Null}
    else{& adb -s $serial shell settings put system font_scale $previous}
    $restored=((& adb -s $serial shell settings get system font_scale) -join '').Trim()
    if($LASTEXITCODE -ne 0 -or $restored -ne $previous){throw 'Font scale was not restored'}
}
@{passed=$passed;font_scale=1.5;previous_font_scale=$previous;restored_font_scale=$restored;application_apk_sha256=(Get-FileHash -Algorithm SHA256 "$projectRoot\app\build\outputs\apk\debug\app-debug.apk").Hash.ToLowerInvariant()} |
    ConvertTo-Json | Set-Content -LiteralPath "$projectRoot\docs\qa\p8\large-font\result.json" -Encoding utf8

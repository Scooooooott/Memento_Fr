param([int[]]$Widths=@(320,360,412),[double[]]$FontScales=@(1.0,1.5,2.0),
      [string]$EvidenceDirectory='docs/qa/ui-restoration/ui6/matrix',
      [ValidateSet('UI6','UI7')][string]$Stage='UI6')
$ErrorActionPreference='Stop'
. "$PSScriptRoot\project-env.ps1"
$serial='emulator-5562'
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if(-not $evidence.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must stay in Memento'}
if(@($Widths | Where-Object {$_ -notin @(320,360,412)}).Count -or @($FontScales | Where-Object {$_ -notin @(1.0,1.5,2.0)}).Count){throw 'Unexpected UI6 matrix configuration'}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$avd=(& adb -s $serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or ($avd.Split("`n")|ForEach-Object{$_.Trim()}) -notcontains 'MementoApi35'){throw 'UI6 requires MementoApi35'}
if(((& adb -s $serial shell getprop ro.kernel.qemu) -join '').Trim() -ne '1'){throw 'Refusing a physical device'}
$originalSize=((& adb -s $serial shell wm size) -join "`n").Trim()
$originalDensity=((& adb -s $serial shell wm density) -join "`n").Trim()
$originalFont=((& adb -s $serial shell settings get system font_scale) -join '').Trim()
if($originalFont -ne 'null' -and $originalFont -notmatch '^\d+(\.\d+)?$'){throw 'Invalid original font scale'}
$physicalSize=[regex]::Match($originalSize,'Physical size: (\d+)x(\d+)')
$densityMatch=[regex]::Match($originalDensity,'(?:Override|Physical) density: (\d+)(?![\s\S]*density:)')
if(-not $physicalSize.Success -or -not $densityMatch.Success){throw 'Could not read display metrics'}
$density=[int]$densityMatch.Groups[1].Value
$height=[int]$physicalSize.Groups[2].Value
$appApk=Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
$testApk=Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$appHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $appApk).Hash.ToLowerInvariant()
$testHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()
$cases=@()
$passed=$false
@{passed=$false;status='running';original_size=$originalSize;original_density=$originalDensity;original_font=$originalFont} |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
try {
    foreach($width in $Widths) { foreach($font in $FontScales) {
        $fontText=$font.ToString('0.0',[System.Globalization.CultureInfo]::InvariantCulture)
        $label="w${width}-f$($fontText.Replace('.','_'))"
        $pixels=[int][Math]::Round($width*$density/160.0,[MidpointRounding]::AwayFromZero)
        & adb -s $serial shell wm size "${pixels}x${height}" | Out-Null
        if($LASTEXITCODE -ne 0){throw 'Could not set emulator width'}
        & adb -s $serial shell settings put system font_scale $fontText
        if($LASTEXITCODE -ne 0){throw 'Could not set emulator font scale'}
        $relative=Join-Path $EvidenceDirectory $label
        & "$PSScriptRoot\verify-device.ps1" -EvidenceDirectory $relative -TestClass 'com.scott.memento.GlobalExperienceInstrumentedTest'
        $log=Get-Content -LiteralPath (Join-Path $projectRoot "$relative/instrumentation.log") -Raw
        if($log -notmatch 'OK \(4 tests\)'){throw 'UI6 matrix requires all four essential-page and form tests'}
        if($appHash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $appApk).Hash.ToLowerInvariant() -or
           $testHash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath $testApk).Hash.ToLowerInvariant()){throw 'APK changed during matrix'}
        $actualSize=((& adb -s $serial shell wm size) -join "`n").Trim()
        $actualFont=((& adb -s $serial shell settings get system font_scale) -join '').Trim()
        if($actualFont -ne $fontText -or $actualSize -notmatch "Override size: ${pixels}x${height}"){throw 'Display settings did not take effect'}
        $cases+=@{configuration=$label;width_dp_requested=$width;font_scale_requested=$font;display_size=$actualSize;
            font_scale_effective=$actualFont;tests=4;passed=$true;log="$relative/instrumentation.log";
            application_apk_sha256=$appHash;test_apk_sha256=$testHash}
        Write-Output "$Stage matrix $label passed"
    }}
    $passed=$true
} finally {
    $override=[regex]::Match($originalSize,'Override size: (\d+x\d+)')
    if($override.Success){& adb -s $serial shell wm size $override.Groups[1].Value | Out-Null}
    else{& adb -s $serial shell wm size reset | Out-Null}
    if($originalFont -eq 'null'){& adb -s $serial shell settings delete system font_scale | Out-Null}
    else{& adb -s $serial shell settings put system font_scale $originalFont | Out-Null}
    $restoredSize=((& adb -s $serial shell wm size) -join "`n").Trim()
    $restoredFont=((& adb -s $serial shell settings get system font_scale) -join '').Trim()
    $restoredDensity=((& adb -s $serial shell wm density) -join "`n").Trim()
    $restored=$restoredSize -eq $originalSize -and $restoredFont -eq $originalFont -and $restoredDensity -eq $originalDensity
    @{passed=($passed -and $restored);scope="$Stage 3 widths x 3 font scales, four production-Activity cases per configuration";
      cases=$cases;tests=($cases|ForEach-Object{$_.tests}|Measure-Object -Sum).Sum;original_size=$originalSize;
      original_font_scale=$originalFont;original_density=$originalDensity;restored_size=$restoredSize;
      restored_font_scale=$restoredFont;restored_density=$restoredDensity;display_settings_restored=$restored;
      application_apk_sha256=$appHash;test_apk_sha256=$testHash} |
      ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8
    if(-not $restored){throw 'Emulator display settings were not restored'}
}

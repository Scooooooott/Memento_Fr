param([string]$EvidenceDirectory='docs/qa/p8',[string]$ApkPath='releases/Memento-0.1.0-debug.apk')
. "$PSScriptRoot\project-env.ps1"
$serial='emulator-5562'
$avd=(& adb -s $serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or ($avd.Split("`n") | ForEach-Object {$_.Trim()}) -notcontains 'MementoApi35'){throw 'Install QA requires MementoApi35'}
if(((& adb -s $serial shell getprop ro.kernel.qemu) -join '').Trim() -ne '1'){throw 'Refusing a physical device'}
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
$release=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $ApkPath))
if(-not $evidence.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase) -or -not $release.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw 'Install evidence and APK must stay in Memento'}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$hash=(Get-FileHash -Algorithm SHA256 $release).Hash.ToLowerInvariant()
& adb -s $serial shell am force-stop com.scott.memento
if($LASTEXITCODE -ne 0){throw 'Could not stop app for installation check'}
$beforeOutput=(& adb -s $serial shell run-as com.scott.memento sha256sum databases/memento_user.db 2>&1) -join ''
$beforeHash=if($beforeOutput -match '^([a-f0-9]{64})\s'){$Matches[1]}else{$null}
$install=& adb -s $serial install -r $release
if($LASTEXITCODE -ne 0 -or ($install -join "`n") -notmatch 'Success'){throw 'Preserving installation failed'}
$afterOutput=(& adb -s $serial shell run-as com.scott.memento sha256sum databases/memento_user.db 2>&1) -join ''
$afterHash=if($afterOutput -match '^([a-f0-9]{64})\s'){$Matches[1]}else{$null}
if($beforeHash -and $beforeHash -ne $afterHash){throw 'Installation changed or removed the existing default database'}
$installedPath=((& adb -s $serial shell pm path com.scott.memento) -join '').Trim() -replace '^package:',''
if($installedPath -notmatch '^/data/app/[^\r\n]+/base\.apk$'){throw 'Unexpected installed APK path'}
$installedOutput=(& adb -s $serial shell sha256sum $installedPath) -join ''
if($LASTEXITCODE -ne 0 -or $installedOutput -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -ne $hash){throw 'Installed APK checksum differs from delivery APK'}
$launch=& adb -s $serial shell am start -W -n com.scott.memento/.MainActivity
if($LASTEXITCODE -ne 0 -or ($launch -join "`n") -notmatch 'Status: ok'){throw 'Default entry point did not start'}
$launch | Set-Content -LiteralPath (Join-Path $evidence 'install-smoke.txt') -Encoding utf8
# Wait for the actual default entry to finish loading its settings or home content.
$ready=$false;$deadline=[DateTime]::UtcNow.AddSeconds(30)
while([DateTime]::UtcNow -lt $deadline) {
    & adb -s $serial shell uiautomator dump /sdcard/Android/data/com.scott.memento/files/qa/p8-default-launch.xml | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not inspect default UI'}
    & adb -s $serial pull /sdcard/Android/data/com.scott.memento/files/qa/p8-default-launch.xml (Join-Path $evidence 'default-launch.xml') | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not retrieve default UI'}
    [xml]$ui=Get-Content -LiteralPath (Join-Path $evidence 'default-launch.xml') -Raw
    $ready=$null -ne $ui.SelectSingleNode("//node[@class='android.widget.EditText' and @enabled='true']") -or $null -ne $ui.SelectSingleNode("//node[@text='开始 / 继续学习' or @text='当前词书进度']")
    if($ready){break}
}
if(-not $ready){throw 'Default entry did not finish loading'}
$appProcess=((& adb -s $serial shell pidof com.scott.memento) -join '').Trim()
if($appProcess -notmatch '^\d+$'){throw 'Application process did not survive cold start'}
& adb -s $serial shell screencap -p /sdcard/Android/data/com.scott.memento/files/qa/p8-default-launch.png
if($LASTEXITCODE -ne 0){throw 'Could not capture default entry'}
& adb -s $serial pull /sdcard/Android/data/com.scott.memento/files/qa/p8-default-launch.png (Join-Path $evidence 'default-launch.png') | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not retrieve default entry capture'}
@{passed=$true;apk_sha256=$hash;installed_apk_sha256=$hash;application_id='com.scott.memento';pid=$appProcess;default_database_existed_before=[bool]$beforeHash;
    default_database_before_install_sha256=$beforeHash;default_database_after_install_sha256=$afterHash;default_database_preserved_during_install=(!$beforeHash -or $beforeHash -eq $afterHash);
    uses_qa_extras=$false;clears_data=$false;ui_ready=$ready;avd='MementoApi35';serial=$serial
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'install.json') -Encoding utf8
$launch

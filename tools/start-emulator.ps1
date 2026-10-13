. "$PSScriptRoot\project-env.ps1"
& adb start-server
if ($LASTEXITCODE -ne 0) { throw 'Memento isolated ADB server did not start' }
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'MementoApi35.avd'
New-Item -ItemType Directory -Force -Path $avdPath | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-35
"@ | Set-Content -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'MementoApi35.ini') -Encoding utf8
@'
AvdId=MementoApi35
avd.ini.displayname=Memento API 35
abi.type=x86_64
tag.id=default
tag.display=Default
image.sysdir.1=system-images\android-35\default\x86_64\
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=software
hw.audioInput=no
disk.dataPartition.size=2G
showDeviceFrame=no
fastboot.forceColdBoot=yes
'@ | Set-Content -LiteralPath (Join-Path $avdPath 'config.ini') -Encoding utf8
$process = Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList @('-avd','MementoApi35','-port','5562','-no-window','-no-snapshot','-no-boot-anim','-no-audio','-gpu','software','-no-metrics') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot\.tools\emulator.log" -RedirectStandardError "$projectRoot\.tools\emulator-error.log"
Write-Output "Memento emulator PID $($process.Id); serial emulator-5562; isolated ADB port 5039"

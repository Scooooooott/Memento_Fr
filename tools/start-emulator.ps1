. "$PSScriptRoot\project-env.ps1"
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'WordsApi35.avd'
New-Item -ItemType Directory -Force $avdPath | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-35
"@ | Set-Content -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'WordsApi35.ini') -Encoding utf8
@'
AvdId=WordsApi35
avd.ini.displayname=Words API 35
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
$process = Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" -ArgumentList @('-avd','WordsApi35','-port','5556','-no-window','-no-snapshot','-no-boot-anim','-no-audio','-gpu','software','-no-metrics') -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot\docs\qa\emulator.log" -RedirectStandardError "$projectRoot\docs\qa\emulator-error.log"
Write-Output "Project emulator process: $($process.Id); adb serial emulator-5556"

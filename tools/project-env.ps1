$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools\gradle-home'
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\home\.android'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools\android-avd'
$env:ANDROID_SDK_HOME = Join-Path $projectRoot '.tools\home'
$env:TEMP = Join-Path $projectRoot '.tools\tmp'
$env:TMP = $env:TEMP
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONPYCACHEPREFIX = Join-Path $projectRoot '.tools\pycache'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
$env:JAVA_TOOL_OPTIONS = "-Duser.home=$projectRoot/.tools/home -Djava.io.tmpdir=$projectRoot/.tools/tmp -XX:-UsePerfData"
foreach ($directory in @($env:GRADLE_USER_HOME,$env:ANDROID_USER_HOME,$env:ANDROID_AVD_HOME,$env:ANDROID_SDK_HOME,$env:TEMP)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

param([string]$EvidenceDirectory='docs/qa/p8/process-recovery')
. "$PSScriptRoot\project-env.ps1"
$evidence=[System.IO.Path]::GetFullPath((Join-Path $projectRoot $EvidenceDirectory))
if(-not $evidence.StartsWith($projectRoot+'\',[System.StringComparison]::OrdinalIgnoreCase)){throw 'Evidence must stay in Memento'}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$serial='emulator-5562'
$avd=(& adb -s $serial emu avd name) -join "`n"
if($LASTEXITCODE -ne 0 -or ($avd.Split("`n") | ForEach-Object {$_.Trim()}) -notcontains 'MementoApi35'){throw 'Recovery QA requires MementoApi35'}
if(((& adb -s $serial shell getprop ro.kernel.qemu) -join '').Trim() -ne '1'){throw 'Refusing a physical device'}
& adb -s $serial install -r "$projectRoot\app\build\outputs\apk\debug\app-debug.apk"
if($LASTEXITCODE -ne 0){throw 'Recovery app installation failed'}
& adb -s $serial install -r "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if($LASTEXITCODE -ne 0){throw 'Recovery test installation failed'}
$cases=@()
foreach($caseName in @('pending','completed','extra','grace')) {
    foreach($phase in @('prepare','verify')) {
        $result=& adb -s $serial shell am instrument -w -r -e class com.scott.memento.ProcessRecoveryInstrumentedTest -e qa_process_phase $phase -e qa_process_case $caseName com.scott.memento.test/androidx.test.runner.AndroidJUnitRunner 2>&1
        $result | Set-Content -LiteralPath (Join-Path $evidence "$caseName-$phase.log") -Encoding utf8
        if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(1 test\)'){throw "Recovery $caseName/$phase failed: $($result | Select-Object -Last 18)"}
        if($phase -eq 'prepare') {
            & adb -s $serial pull "/sdcard/Android/data/com.scott.memento/files/qa/p8-process-$caseName-expected.json" (Join-Path $evidence "$caseName-expected.json") | Out-Null
            if($LASTEXITCODE -ne 0){throw 'Could not read recovery fixture metadata'}
            $fixture=Get-Content -LiteralPath (Join-Path $evidence "$caseName-expected.json") -Raw | ConvertFrom-Json
            if($fixture.database -ne 'memento-test-process-recovery.db'){throw 'Refusing a non-test database'}
            $launchArgs=@('shell','am','start','-W','-n','com.scott.memento/.MainActivity','--es','qa_user_database',$fixture.database,'--el','qa_clock_millis',([string]$fixture.epoch))
            $before=& adb -s $serial @launchArgs
            if($LASTEXITCODE -ne 0 -or ($before -join "`n") -notmatch 'Status: ok'){throw 'Pre-stop launch failed'}
            $before | Set-Content -LiteralPath (Join-Path $evidence "$caseName-launch-before.txt") -Encoding utf8
            $oldProcess=((& adb -s $serial shell pidof com.scott.memento) -join '').Trim()
            if($oldProcess -notmatch '^\d+$'){throw 'Application process did not start'}
            & adb -s $serial shell am force-stop com.scott.memento
            if($LASTEXITCODE -ne 0){throw 'Force-stop failed'}
            $stopped=((& adb -s $serial shell pidof com.scott.memento) -join '').Trim()
            if($stopped){throw 'Application process is still running'}
            $after=& adb -s $serial @launchArgs
            if($LASTEXITCODE -ne 0 -or ($after -join "`n") -notmatch 'Status: ok'){throw 'Cold restart failed'}
            $after | Set-Content -LiteralPath (Join-Path $evidence "$caseName-launch-after.txt") -Encoding utf8
            $newProcess=((& adb -s $serial shell pidof com.scott.memento) -join '').Trim()
            if($newProcess -notmatch '^\d+$' -or $newProcess -eq $oldProcess){throw 'Cold restart did not create a different process'}
            $cases+=@{case=$caseName;before_pid=$oldProcess;after_pid=$newProcess;stopped_pid_empty=$true;fixed_clock_epoch=$fixture.epoch;database=$fixture.database}
        }
    }
    & adb -s $serial pull "/sdcard/Android/data/com.scott.memento/files/qa/p8-process-$caseName-verified.json" (Join-Path $evidence "$caseName-verified.json") | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Missing recovery verification'}
    $verified=Get-Content -LiteralPath (Join-Path $evidence "$caseName-verified.json") -Raw | ConvertFrom-Json
    if(-not $verified.passed){throw 'Recovery verification did not pass'}
    Write-Output "Recovered: $caseName ($oldProcess -> $newProcess)"
}
& adb -s $serial pull '/sdcard/Android/data/com.scott.memento/files/qa/.' (Join-Path $evidence 'screenshots') | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not retrieve recovery screenshots'}
@{passed=$true;emulator=$serial;avd='MementoApi35';cases=$cases;instrumentation_invocations=8;uses_force_stop=$true;clears_default_database=$false;
    application_apk_sha256=(Get-FileHash -Algorithm SHA256 "$projectRoot\app\build\outputs\apk\debug\app-debug.apk").Hash.ToLowerInvariant();
    test_apk_sha256=(Get-FileHash -Algorithm SHA256 "$projectRoot\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk").Hash.ToLowerInvariant()
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'result.json') -Encoding utf8

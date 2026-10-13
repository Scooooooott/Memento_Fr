param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'), [switch]$RefreshDependencies)
. "$PSScriptRoot\project-env.ps1"
& 'E:\Programs\Python\Python312\python.exe' -B "$projectRoot\tools\check_content_assets.py"
if ($LASTEXITCODE -ne 0) { throw 'Unified content assets must be regenerated/verified before building' }
$mirrorReady = $false
try { $mirrorReady = (Invoke-WebRequest -Uri 'http://127.0.0.1:18763/com/android/tools/build/gradle/8.7.3/gradle-8.7.3.pom' -Method Head -TimeoutSec 2).StatusCode -eq 200 } catch { }
if (-not $mirrorReady) {
    $mirror = Start-Process -FilePath 'E:\Programs\Python\Python312\python.exe' -ArgumentList @('-B', "$projectRoot\tools\dependency_mirror.py") -WindowStyle Hidden -PassThru -RedirectStandardOutput "$projectRoot\.tools\mirror.log" -RedirectStandardError "$projectRoot\.tools\mirror-error.log"
    for ($attempt=0; $attempt -lt 20; $attempt++) {
        try { if ((Invoke-WebRequest -Uri 'http://127.0.0.1:18763/com/android/tools/build/gradle/8.7.3/gradle-8.7.3.pom' -Method Head -TimeoutSec 1).StatusCode -eq 200) { $mirrorReady=$true; break } } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $mirrorReady) { throw 'Dependency mirror did not start' }
}
Push-Location $projectRoot
try {
    [string[]]$refreshArgs = @()
    if ($RefreshDependencies) { $refreshArgs = @('--refresh-dependencies') }
    & "$projectRoot\.tools\gradle-8.9\bin\gradle.bat" @Tasks @refreshArgs --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-PmementoMirror=http://127.0.0.1:18763' --console=plain --project-cache-dir "$projectRoot\.gradle"
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

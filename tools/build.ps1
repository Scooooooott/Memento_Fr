param([string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'))
. "$PSScriptRoot\project-env.ps1"
Push-Location $projectRoot
try {
    & "$projectRoot\gradlew.bat" @Tasks --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

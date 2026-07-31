$ErrorActionPreference = "Stop"

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$JdkDir = Join-Path $RootDir ".local\jdk-21"
$JavaPath = Join-Path $JdkDir "bin\java.exe"
$GradlewPath = Join-Path $RootDir "gradlew.bat"

if (-not (Test-Path $JavaPath)) {
    throw "Project-local JDK not found. Run .\scripts\bootstrap-jdk.ps1 first."
}

if (-not (Test-Path $GradlewPath)) {
    throw "Gradle Wrapper not found."
}

$PreviousEnvironment = @{
    JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
    GRADLE_USER_HOME = [Environment]::GetEnvironmentVariable("GRADLE_USER_HOME", "Process")
    PATH = [Environment]::GetEnvironmentVariable("PATH", "Process")
}

try {
    $env:JAVA_HOME = $JdkDir
    $env:GRADLE_USER_HOME = Join-Path $RootDir ".local\gradle-home"
    $env:PATH = "$JdkDir\bin;$env:PATH"

    & $GradlewPath @args
    $GradleExitCode = $LASTEXITCODE
}
finally {
    foreach ($Name in $PreviousEnvironment.Keys) {
        $PreviousValue = $PreviousEnvironment[$Name]
        if ($null -eq $PreviousValue) {
            Remove-Item "Env:$Name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item "Env:$Name" $PreviousValue
        }
    }
}

exit $GradleExitCode

# Native build driver for Windows: loads VS C++ env, points JAVA_HOME + GRAALVM_HOME
# at GraalVM 25, and runs the standard GraalVM Native Image build (`mvn -Pnative`).
#
# Use 'Continue' (not 'Stop'): Maven and native-image both write progress to stderr,
# and with $ErrorActionPreference = 'Stop' PowerShell treats every native-process stderr
# line as a terminating error. We check $LASTEXITCODE explicitly after mvn instead.
$ErrorActionPreference = 'Continue'

# Run from the repo root no matter where the user invoked the script from.
Set-Location (Join-Path $PSScriptRoot '..')

$vcvarsCandidates = @(
    "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat",
    "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat",
    "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat",
    "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
)
$vcvars = $vcvarsCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $vcvars) {
    Write-Host "vcvars64.bat not found in standard Visual Studio 2022 locations." -ForegroundColor Red
    Write-Host "Install Visual Studio 2022 with the 'Desktop development with C++' workload, or run from an x64 Native Tools Command Prompt." -ForegroundColor Yellow
    exit 1
}

# Locate the latest installed GraalVM 25.x under C:\Java\JDKs, or honor an existing GRAALVM_HOME.
$graal = Get-ChildItem "C:\Java\JDKs" -Directory -Filter "graalvm-jdk-25*" -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending |
         Select-Object -First 1
if ($graal) {
    $graalPath = $graal.FullName
} elseif ($env:GRAALVM_HOME -and (Test-Path (Join-Path $env:GRAALVM_HOME 'bin\native-image.cmd'))) {
    $graalPath = $env:GRAALVM_HOME
} else {
    Write-Host "No GraalVM 25 install found under C:\Java\JDKs (expected 'graalvm-jdk-25*') and GRAALVM_HOME is not set." -ForegroundColor Red
    Write-Host "Install Oracle GraalVM for JDK 25 from:" -ForegroundColor Yellow
    Write-Host "  https://www.oracle.com/java/technologies/downloads/#graalvmjava25" -ForegroundColor Yellow
    Write-Host "Extract under C:\Java\JDKs\ or set GRAALVM_HOME." -ForegroundColor Yellow
    exit 1
}
Write-Host "Found GraalVM at $graalPath"

Write-Host "Loading vcvars64 env from $vcvars..."
$lines = & cmd /d /c "`"$vcvars`" >nul && set"
foreach ($line in $lines) {
    if ($line -match '^([^=]+)=(.*)$') {
        Set-Item -Path "env:$($Matches[1])" -Value $Matches[2]
    }
}

$env:JAVA_HOME    = $graalPath
$env:GRAALVM_HOME = $graalPath
$env:PATH         = "$graalPath\bin;$env:PATH"

Write-Host "=== JAVA_HOME=$env:JAVA_HOME ==="
& "$graalPath\bin\java.exe" -version
Write-Host "=== native-image ==="
& "$graalPath\bin\native-image.cmd" --version

Write-Host "=== Starting native build ==="
& mvn -Pnative -DskipTests clean package
exit $LASTEXITCODE

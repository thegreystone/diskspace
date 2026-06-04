# Native build driver: loads VS C++ env, points GRAALVM_HOME + JAVA_HOME at GraalVM 21 LTS,
# runs Gluon native build.
#
# Why an explicit GRAALVM_HOME override: gluonfx-maven-plugin reads GRAALVM_HOME (not JAVA_HOME)
# to locate native-image. If a system-wide GRAALVM_HOME exists pointing elsewhere, the plugin
# will use that instead — silently. We override both vars here.
#
# Why GraalVM 21: Oracle GraalVM 25's Substrate VM tightened its JNI version allow-list and
# rejects 0x10002 (JNI_VERSION_1_2), which is what JavaFX's `glass` library returns from
# JNI_OnLoad. The result is `UnsatisfiedLinkError: Unsupported JNI version 0x10002, required
# by glass` at startup of the produced exe. GraalVM 22 still accepts 0x10002. GraalVM 21 LTS
# is the LTS-supported landing pad.

# Use 'Continue' (not 'Stop'): Maven and native-image both write progress to stderr,
# and with $ErrorActionPreference = 'Stop' PowerShell treats every native-process stderr
# line as a terminating error. The script would bail mid-build (or right after a
# successful "BUILD SUCCESS" while the link step was still finalising artifacts) and
# leave nothing in target/gluonfx. We do an explicit $LASTEXITCODE check after mvn
# instead, which correctly distinguishes "wrote to stderr" from "exited non-zero".
$ErrorActionPreference = 'Continue'

# Run from the repo root no matter where the user invoked the script from,
# so `mvn` finds pom.xml.
Set-Location (Join-Path $PSScriptRoot '..')

$vcvars = "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if (-not (Test-Path $vcvars)) {
    Write-Host "vcvars64.bat not found at $vcvars" -ForegroundColor Red
    Write-Host "Install Visual Studio 2022 Community with the 'Desktop development with C++' workload, or edit \$vcvars in this script to point at your install." -ForegroundColor Yellow
    exit 1
}

# Locate the latest installed GraalVM 21.x under C:\Java\JDKs.
$graal = Get-ChildItem "C:\Java\JDKs" -Directory -Filter "graalvm-jdk-21*" -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending |
         Select-Object -First 1
if (-not $graal) {
    Write-Host "No GraalVM 21 install found under C:\Java\JDKs (expected directory matching 'graalvm-jdk-21*')." -ForegroundColor Red
    Write-Host "Install Oracle GraalVM for JDK 21 LTS from:" -ForegroundColor Yellow
    Write-Host "  https://www.oracle.com/java/technologies/downloads/#graalvmjava21" -ForegroundColor Yellow
    Write-Host "Extract under C:\Java\JDKs\ so the path looks like C:\Java\JDKs\graalvm-jdk-21.0.x+y.z\." -ForegroundColor Yellow
    exit 1
}
$graalPath = $graal.FullName
Write-Host "Found GraalVM at $graalPath"

Write-Host "Loading vcvars64 env..."
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
& mvn -Pnative gluonfx:build
exit $LASTEXITCODE

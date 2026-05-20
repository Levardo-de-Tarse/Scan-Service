#Requires -Version 5.1
<#
.SYNOPSIS
  Builds a portable Windows folder + ZIP with ScannerService.exe and an embedded JRE (jpackage).

.DESCRIPTION
  Requires JDK 17+ on PATH or JAVA_HOME (jpackage.exe). WiX is NOT required for --type app-image.
  Optional: copy a local NAPS2 installation into naps2-bin/ next to the app (see -Naps2Bundle).

.PARAMETER SkipTests
  Skip Maven tests when this script runs mvnw package (ignored if -SkipMvn).

.PARAMETER SkipMvn
  Assume target/scanner-service.jar already exists (e.g. when Maven runs this script after package).

.PARAMETER Naps2Bundle
  Set to 'true' to copy NAPS2 from -Naps2Source into dist/ScannerService/naps2-bin (GPL — see LEGAL-NAPS2.md).

.PARAMETER Naps2Source
  Directory of an installed NAPS2 (default: Program Files).
#>
param(
    [switch] $SkipTests,
    [switch] $SkipMvn,
    [string] $Naps2Bundle = 'false',
    [string] $Naps2Source = 'C:\Program Files\NAPS2'
)

$doBundleNaps2 = ($Naps2Bundle -eq 'true')

$ErrorActionPreference = 'Stop'
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Set-Location $ProjectRoot

function Find-JPackage {
    if ($env:JAVA_HOME) {
        $jp = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'
        if (Test-Path $jp) { return $jp }
    }
    $cmd = Get-Command 'jpackage' -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$jpackage = Find-JPackage
if (-not $jpackage) {
    throw "jpackage.exe not found. Install JDK 17 or newer (e.g. Eclipse Temurin) and set JAVA_HOME, or add JDK bin to PATH."
}

if (-not $SkipMvn) {
    $mvnArgs = @('package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    Write-Host ">> mvnw $($mvnArgs -join ' ')" -ForegroundColor Cyan
    & (Join-Path $ProjectRoot 'mvnw.cmd') @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }
}

$jar = Join-Path $ProjectRoot 'target\scanner-service.jar'
if (-not (Test-Path $jar)) {
    throw "Expected JAR not found: $jar"
}

$destDir = Join-Path $ProjectRoot 'dist'
$stage = Join-Path $destDir 'jpackage-input'
if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'scanner-service.jar') -Force

$appName = 'ScannerService'
$appRoot = Join-Path $destDir $appName
if (Test-Path $appRoot) {
    Write-Host ">> Removing old $appRoot" -ForegroundColor DarkYellow
    Remove-Item -LiteralPath $appRoot -Recurse -Force
}

Write-Host ">> jpackage (app-image, embedded runtime)..." -ForegroundColor Cyan
$jpackageArgs = @(
    '--input', $stage,
    '--name', $appName,
    '--main-jar', 'scanner-service.jar',
    '--type', 'app-image',
    '--dest', $destDir,
    '--app-version', '1.0.0',
    '--vendor', 'ScannerService',
    '--description', 'REST API for scanning via NAPS2',
    '--copyright', '2026',
    '--java-options', '-Xmx512m',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--win-console'
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

$exe = Join-Path $appRoot "$appName.exe"
if (-not (Test-Path $exe)) {
    throw "Expected launcher not found: $exe"
}

if ($doBundleNaps2) {
    if (-not (Test-Path $Naps2Source)) {
        throw "NAPS2 source directory not found: $Naps2Source`nInstall NAPS2 or pass -Naps2Source path to your copy."
    }
    $console = Join-Path $Naps2Source 'NAPS2.Console.exe'
    if (-not (Test-Path $console)) {
        throw "NAPS2.Console.exe not found under: $Naps2Source"
    }
    $destNaps2 = Join-Path $appRoot 'naps2-bin'
    if (Test-Path $destNaps2) { Remove-Item -LiteralPath $destNaps2 -Recurse -Force }
    New-Item -ItemType Directory -Path $destNaps2 -Force | Out-Null
    Write-Host ">> Copying NAPS2 from $Naps2Source -> $destNaps2" -ForegroundColor Cyan
    Get-ChildItem -LiteralPath $Naps2Source -Force | ForEach-Object {
        if ($_.Name -like 'unins000*') { return }
        $target = Join-Path $destNaps2 $_.Name
        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
    }
    $legal = Join-Path $ProjectRoot 'LEGAL-NAPS2.md'
    if (Test-Path $legal) {
        Copy-Item -LiteralPath $legal -Destination (Join-Path $destNaps2 'BUNDLED-SCANNER-SERVICE-NOTE.md') -Force
    }
}

$zipName = if ($doBundleNaps2) { 'ScannerService-windows-with-naps2.zip' } else { 'ScannerService-windows-portable.zip' }
$zipPath = Join-Path $destDir $zipName
if (Test-Path $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Write-Host ">> Zipping ($zipName)..." -ForegroundColor Cyan
Compress-Archive -LiteralPath $appRoot -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  Folder: $appRoot"
Write-Host "  Run:    $exe"
Write-Host "  Zip:    $zipPath"
Write-Host ""
if ($doBundleNaps2) {
    Write-Host "NAPS2 was copied into naps2-bin. You are responsible for GPL compliance (see LEGAL-NAPS2.md)." -ForegroundColor DarkGray
    Write-Host "TWAIN/WIA drivers for your hardware must still be installed in Windows on each PC." -ForegroundColor DarkGray
} else {
    Write-Host "Install NAPS2 on the target PC, or rebuild with -Naps2Bundle true / mvn -Pwindows-exe -Dnaps2.bundle=true" -ForegroundColor DarkGray
}

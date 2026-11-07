param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\target\clix-1.0-SNAPSHOT.jar'),
    [string]$NativeExe = (Join-Path $PSScriptRoot '..\src\main\native\build\Release\clix.exe'),
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'clix\bin')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "找不到安装包 $Jar，请先运行 mvn package。"
}
if (-not (Test-Path -LiteralPath $NativeExe -PathType Leaf)) {
    $alternate = Join-Path $PSScriptRoot '..\src\main\native\build\clix.exe'
    if (Test-Path -LiteralPath $alternate -PathType Leaf) { $NativeExe = $alternate }
    else { throw '找不到原生 clix.exe。请先运行 tools/build-clix-native-windows.ps1。' }
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '需要先安装 Java 17 或更高版本。'
}
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $InstallDir 'clix.jar') -Force
Copy-Item -LiteralPath $NativeExe -Destination (Join-Path $InstallDir 'clix.exe') -Force
# No clix.cmd: invoking the batch wrapper would restore its conflicting Ctrl+C behavior.
$oldBatch = Join-Path $InstallDir 'clix.cmd'
if (Test-Path -LiteralPath $oldBatch) { Remove-Item -LiteralPath $oldBatch -Force }
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $InstallDir) {
    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $InstallDir } else { "$userPath;$InstallDir" }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
}
Write-Host '安装完成。请重开终端，执行 clix login，然后在项目目录运行 clix（默认进入 remote）。'

param(
    [ValidateSet('Release','Debug')][string]$Configuration = 'Release',
    [string]$BuildDir = (Join-Path $PSScriptRoot '..\src\main\native\build')
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') {
    throw '原生 Windows 启动器必须在 Windows/MSVC 或 MinGW-w64 环境构建。'
}
if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    throw '缺少 cmake。请安装 CMake 和 Visual Studio C++ Build Tools 或 MinGW-w64。'
}
$Source = Join-Path $PSScriptRoot '..\src\main\native'
& cmake -S $Source -B $BuildDir
if ($LASTEXITCODE -ne 0) { throw 'CMake configure 失败。' }
& cmake --build $BuildDir --config $Configuration
if ($LASTEXITCODE -ne 0) { throw 'clix.exe 构建失败。' }
$exe = Join-Path $BuildDir "$Configuration\clix.exe"
if (-not (Test-Path -LiteralPath $exe)) { $exe = Join-Path $BuildDir 'clix.exe' }
if (-not (Test-Path -LiteralPath $exe)) { throw '编译完成，但未找到 clix.exe。' }
Write-Host "Windows 原生启动器构建成功: $exe"

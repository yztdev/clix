# Compatibility stub: the current local-first release uses clix.cmd + a bundled Java Runtime.
# No C compiler, CMake, MSVC, or MinGW toolchain is required to build/install this release.
$ErrorActionPreference = 'Stop'
Write-Host '当前 CLIX 本地版本不再构建原生 C clix.exe。' -ForegroundColor Yellow
Write-Host '请先运行 tools\build-clix-runtime-windows.ps1，再运行 tools\install-clix-windows.ps1。'
exit 0

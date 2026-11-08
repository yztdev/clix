param([string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'clix'))
$ErrorActionPreference = 'Stop'
$binDir = Join-Path $InstallRoot 'bin'
$cmd = Join-Path $binDir 'clix.cmd'
$jar = Join-Path $InstallRoot 'lib\clix.jar'
$java = Join-Path $InstallRoot 'runtime\bin\java.exe'
foreach ($path in @($cmd, $jar, $java)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "缺少安装文件：$path" }
}
if (Test-Path -LiteralPath (Join-Path $binDir 'clix.exe')) {
    throw '检测到旧 clix.exe；它会优先于 clix.cmd，请重新运行安装脚本清理旧入口。'
}
$resolved = (Get-Command clix -ErrorAction Stop).Source
if (-not [string]::Equals([IO.Path]::GetFullPath($resolved), [IO.Path]::GetFullPath($cmd), [StringComparison]::OrdinalIgnoreCase)) {
    throw "PATH 中 clix 未指向本地批处理入口：$resolved"
}
Write-Host 'CLIX 本地命令入口检查通过。' -ForegroundColor Green
Write-Host '手工验收：'
Write-Host '1. 在项目目录执行 clix，确认启动本地 Agent。'
Write-Host '2. 确认文件和命令均在当前 Windows 机器执行。'
Write-Host '3. 在 Windows 原生终端执行长命令并按 Ctrl+C，验证 JNA 控制台取消桥接与 Job Object 清理。'
Write-Host '4. Linux cgroup v2 进程监管不属于当前阶段。'
Write-Host '5. clix remote、clix login 和 clix serve --remote 应被拒绝。'

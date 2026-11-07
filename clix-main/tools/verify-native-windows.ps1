<#
Manual real-console acceptance checklist (V1.3 local-first Windows runtime).
Run in cmd, PowerShell and Windows Terminal on a real Windows host.
This script checks build/install wiring; real keyboard Ctrl+C MUST be manually
pressed in an interactive local CLIX session. No simulated event is accepted.
#>
param([string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'clix\bin'))
$ErrorActionPreference = 'Stop'
$exe = Join-Path $InstallDir 'clix.exe'
$jar = Join-Path $InstallDir 'clix.jar'
if (-not (Test-Path $exe -PathType Leaf)) { throw "缺少 $exe" }
if (-not (Test-Path $jar -PathType Leaf)) { throw "缺少 $jar" }
if (Test-Path (Join-Path $InstallDir 'clix.cmd')) { throw '存在旧 clix.cmd，可能抢占 PATH 启动入口' }
$resolved = (Get-Command clix -ErrorAction Stop).Source
if (-not [string]::Equals([IO.Path]::GetFullPath($resolved), [IO.Path]::GetFullPath($exe), [StringComparison]::OrdinalIgnoreCase)) {
    throw "PATH 中 clix 未指向原生启动器: $resolved"
}
Write-Host '构建/安装入口检查通过。必须手工完成以下验收（不要把本检查当作通过实机验收）：'
Write-Host '1. 默认 clix 启动本地 Agent；clix remote 显式进入远程 Agent 模式。'
Write-Host '2. 本地 Windows execute_command 使用 PowerShell + Job Object；真键盘 Ctrl+C 取消当前 Turn。'
Write-Host '3. Linux cgroup v2 进程监管暂不属于本阶段验收，后续单独扩展。'
Write-Host '4. Windows Job Object、多级子进程和不同盘符工作目录需要实机验收。'
Write-Host '5. 远程模式断网恢复与跨机器取消仍按远程专项文档单独验收。'

<#
Manual real-console acceptance checklist (V1.2 phase 4 / completion criterion 12).
Run in cmd, PowerShell and Windows Terminal on a real Windows host.
This script checks build/install wiring; real keyboard Ctrl+C MUST be manually
pressed in an interactive CLIX remote session. No simulated event is accepted.
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
Write-Host '1. cmd / PowerShell / Windows Terminal 真键盘 Ctrl+C 首次取消，第二次强制清理。'
Write-Host '2. 取消后 10 秒内出现提示符；同一 WebSocket 可以继续，登录态保持。'
Write-Host '3. WSL cgroup v2 + 管道/多级子进程/nohup/setsid 清空；无延迟写入。'
Write-Host '4. Windows Job Object、严格/PGID_ONLY 降级、C盘/其他盘 DrvFS。'
Write-Host '5. 断网、终端关闭、JVM 退出、未发送 effect 恢复；全库回归通过。'

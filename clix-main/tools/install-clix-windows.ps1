param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\target\clix-1.0-SNAPSHOT.jar'),
    [string]$RuntimeImage = (Join-Path $PSScriptRoot '..\target\clix-runtime'),
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'clix')
)

$ErrorActionPreference = 'Stop'
$binDir = Join-Path $InstallRoot 'bin'
$libDir = Join-Path $InstallRoot 'lib'
$runtimeDir = Join-Path $InstallRoot 'runtime'

if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "找不到 CLIX JAR：$Jar。请先执行 mvn -Pquick -DskipTests clean package。"
}
if (-not (Test-Path -LiteralPath (Join-Path $RuntimeImage 'bin\java.exe') -PathType Leaf)) {
    throw "找不到内置 Java Runtime：$RuntimeImage。请先在构建机执行 tools\build-clix-runtime-windows.ps1。"
}

New-Item -ItemType Directory -Path $binDir, $libDir -Force | Out-Null
if (Test-Path -LiteralPath $runtimeDir) {
    Remove-Item -LiteralPath $runtimeDir -Recurse -Force
}
New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $libDir 'clix.jar') -Force
Copy-Item -Path (Join-Path $RuntimeImage '*') -Destination $runtimeDir -Recurse -Force

# .EXE takes precedence over .CMD through PATHEXT. Remove the old native launcher so `clix`
# always resolves to the requested batch entry point in this local-first release.
$oldExe = Join-Path $binDir 'clix.exe'
if (Test-Path -LiteralPath $oldExe) { Remove-Item -LiteralPath $oldExe -Force }
$oldJar = Join-Path $binDir 'clix.jar'
if (Test-Path -LiteralPath $oldJar) { Remove-Item -LiteralPath $oldJar -Force }

$cmdPath = Join-Path $binDir 'clix.cmd'
$cmd = @'
@echo off
setlocal
set "CLIX_HOME=%~dp0.."
for %%I in ("%CLIX_HOME%") do set "CLIX_HOME=%%~fI"
set "JAVA_EXE=%CLIX_HOME%\runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
  echo CLIX bundled Java runtime is missing: "%JAVA_EXE%" 1>&2
  exit /b 2
)
"%JAVA_EXE%" -jar "%CLIX_HOME%\lib\clix.jar" %*
exit /b %ERRORLEVEL%
'@
Set-Content -LiteralPath $cmdPath -Value $cmd -Encoding Ascii

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$entries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($entries -notcontains $binDir) {
    $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $binDir } else { "$userPath;$binDir" }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
}

Write-Host ''
Write-Host 'CLIX 本地 Agent 安装完成。' -ForegroundColor Green
Write-Host "启动命令：clix"
Write-Host "入口文件：$cmdPath"
Write-Host '请关闭并重新打开终端，使 PATH 更新生效。'
Write-Host '当前版本仅开放本地 Agent；远程 Agent 和远程登录入口暂未开放。'

param(
    [string]$Jar = (Join-Path $PSScriptRoot '..\target\clix-1.0-SNAPSHOT.jar'),
    [string]$RuntimeImage = (Join-Path $PSScriptRoot '..\target\clix-runtime'),
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'clix'),
    [switch]$SkipPath
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

New-Item -ItemType Directory -Path $binDir, $libDir, $runtimeDir -Force | Out-Null
try {
    Copy-Item -LiteralPath $Jar -Destination (Join-Path $libDir 'clix.jar') -Force
} catch {
    throw "无法更新 $libDir\clix.jar，可能仍有 clix 进程在运行：$($_.Exception.Message)"
}
try {
    # 覆盖复制而不是先删目录：jvm.dll 被占用时删目录会失败，且删一半会留下坏 runtime。
    Copy-Item -Path (Join-Path $RuntimeImage '*') -Destination $runtimeDir -Recurse -Force
} catch {
    Write-Warning "内置 Runtime 未完全更新（可能正被占用）：$($_.Exception.Message)"
}

# Prefer the native launcher: a .cmd wrapper makes cmd.exe print
# "Terminate batch job (Y/N)?" on Ctrl+C and answering Yes can leave Java detached.
$exePath = Join-Path $binDir 'clix.exe'
$cmdPath = Join-Path $binDir 'clix.cmd'
$oldJar = Join-Path $binDir 'clix.jar'
if (Test-Path -LiteralPath $oldJar) { Remove-Item -LiteralPath $oldJar -Force }

$launcherSource = Join-Path $PSScriptRoot 'ClixLauncher.cs'
$cscCandidates = @(
    (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
    (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
)
$csc = $cscCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$launcherBuilt = $false
if ($csc -and (Test-Path -LiteralPath $launcherSource)) {
    & $csc '/nologo' '/target:exe' '/platform:anycpu' "/out:$exePath" $launcherSource | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $exePath)) {
        throw '编译 clix.exe 失败（通常是仍有 clix 进程在运行）：请退出所有 clix 后重试。'
    }
    $launcherBuilt = $true
}
if ($launcherBuilt) {
    Remove-Item -LiteralPath $cmdPath -Force -ErrorAction SilentlyContinue
} else {
    Write-Warning 'csc.exe 不可用，回退到 clix.cmd：Ctrl+C 时 cmd 可能询问“终止批处理操作吗”。'
    if (Test-Path -LiteralPath $exePath) { Remove-Item -LiteralPath $exePath -Force }
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
}
$entryPath = if ($launcherBuilt) { $exePath } else { $cmdPath }

if (-not $SkipPath) {
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entries = @($userPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($entries -notcontains $binDir) {
        $newPath = if ([string]::IsNullOrWhiteSpace($userPath)) { $binDir } else { "$userPath;$binDir" }
        [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    }
}

Write-Host ''
Write-Host 'CLIX 本地 Agent 安装完成。' -ForegroundColor Green
Write-Host "启动命令：clix"
Write-Host "入口文件：$entryPath"
Write-Host '请关闭并重新打开终端，使 PATH 更新生效。'
Write-Host '当前版本仅开放本地 Agent；远程 Agent 和远程登录入口暂未开放。'

param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputDir = (Join-Path $PSScriptRoot '..\target\clix-runtime')
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    $jlinkCommand = Get-Command jlink.exe -ErrorAction SilentlyContinue
    if ($jlinkCommand) {
        $JdkHome = Split-Path -Parent (Split-Path -Parent $jlinkCommand.Source)
    }
}
if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw '找不到 JDK。请设置 JAVA_HOME，或将 JDK 的 bin 目录加入 PATH。需要完整 JDK 17 或更高版本。'
}
$jlink = Join-Path $JdkHome 'bin\jlink.exe'
if (-not (Test-Path -LiteralPath $jlink -PathType Leaf)) {
    throw "找不到 jlink：$jlink。需要完整 JDK，而不是单独 JRE。"
}

if (Test-Path -LiteralPath $OutputDir) {
    Remove-Item -LiteralPath $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputDir) -Force | Out-Null

# java.se covers the standard Java SE modules used by CLIX/JLine/Jackson/OkHttp;
# add the commonly required JDK service/crypto modules explicitly.
$modules = 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.management,jdk.naming.dns,jdk.zipfs,jdk.localedata'
& $jlink --add-modules $modules --bind-services --strip-debug --no-header-files --no-man-pages --compress=2 --output $OutputDir
if ($LASTEXITCODE -ne 0) { throw 'jlink 创建 CLIX Runtime 失败。' }
if (-not (Test-Path -LiteralPath (Join-Path $OutputDir 'bin\java.exe'))) {
    throw 'Runtime 创建结束，但没有找到 bin\java.exe。'
}
Write-Host "CLIX 内置 Java Runtime 已生成：$OutputDir" -ForegroundColor Green

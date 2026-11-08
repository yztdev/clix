# CLIX Local-only CLI Release

当前发布范围只包含本地 Agent。

## 用户入口

- `clix`：通过 `%LOCALAPPDATA%\clix\bin\clix.cmd` 启动本地 Agent。
- `clix remote`：禁用。
- `clix login`：禁用。
- `clix serve --remote`：禁用。

远程实现类暂时保留在源码树中，供未来重新设计使用，但 Main 不再分派这些入口。当前安装脚本不会要求或安装远程登录凭证，也不会启动远程服务。

## Windows 安装

1. 使用 JDK 17+ 在构建机运行 Maven package。
2. 使用 `tools/build-clix-runtime-windows.ps1` 通过 `jlink` 生成内置 Runtime。
3. 使用 `tools/install-clix-windows.ps1` 安装 JAR、Runtime 和 `clix.cmd`。
4. 重新打开终端，在任意目录运行 `clix`。

最终用户不需要安装 Java、Maven、CMake、GCC 或 Visual Studio Build Tools。

## Ctrl+C

本地 Windows CLI 通过 JNA 注册 `SetConsoleCtrlHandler`，将 Ctrl+C 事件异步转交给当前 Turn 取消逻辑；该路径必须在真实 Windows 控制台中验收。原生 C 启动器不再是当前发布的必要组件。

# CLIX Local-only CLI Release

当前发布范围只包含本地 Agent。

## 用户入口

- `clix`：通过 `%LOCALAPPDATA%\clix\bin\clix.exe` 启动本地 Agent。这是一个很小的原生启动器（`tools/ClixLauncher.cs` 编译而来），直接拉起内置 JVM；`csc.exe` 不可用时安装脚本回退到 `clix.cmd`。
- `clix remote`：禁用。
- `clix login`：禁用。
- `clix serve --remote`：禁用。

远程实现类暂时保留在源码树中，供未来重新设计使用，但 Main 不再分派这些入口。当前安装脚本不会要求或安装远程登录凭证，也不会启动远程服务。

## Windows 安装

1. 使用 JDK 17+ 在构建机运行 Maven package。
2. 使用 `tools/build-clix-runtime-windows.ps1` 通过 `jlink` 生成内置 Runtime。
3. 使用 `tools/install-clix-windows.ps1` 安装 JAR、Runtime 和原生启动器（`clix.exe`；无 `csc.exe` 时回退为 `clix.cmd`）。
4. 重新打开终端，在任意目录运行 `clix`。

最终用户不需要安装 Java、Maven、CMake、GCC 或 Visual Studio Build Tools。

## Ctrl+C 与 ESC

- 任务运行中：第一次 `Ctrl+C`（或 `ESC`）取消当前 Turn，回到提示符；2 秒内再按一次 `Ctrl+C` 强制退出。
- 空闲提示符：第一次 `Ctrl+C` 清空当前输入并装填 5 秒退出窗口，窗口内再按一次退出。
- Windows 上通过 JLine/JDK 的 `SetConsoleCtrlHandler` 路径接管 SIGINT。启动器不是 `.cmd`，所以不会再出现 cmd 的“终止批处理操作吗(Y/N)?”提示；Ctrl+C 事件直接由 JVM 处理。
- 退出时由 shutdown hook 收掉 `execute_command` 启动的进程树、MCP 子进程、后台任务和微信通道；被强杀（任务管理器结束进程）时不保证执行。
- 该行为必须在真实 Windows 控制台和 POSIX 终端中各验收一次。

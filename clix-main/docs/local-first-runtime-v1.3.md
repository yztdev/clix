# CLIX V1.3：Local-first Agent Runtime

> 状态：本阶段实施范围与验收基线
> 核心原则：Agent、工具注册、工具执行和进程监管默认位于当前运行 CLIX 的机器；模型推理可以通过远程 API 完成。

## 1. 目标

V1.3 先把 CLIX 做成类似 Claude Code / Codex CLI 的本地优先 Agent：

- `clix` 默认启动本地 Agent Runtime，不自动连接 Remote Agent Server。
- Agent Core、Thread/Turn 管理、Tool Registry、文件工具和命令工具都在当前机器运行。
- 模型请求仍可访问远程 LLM API；模型 API 不决定工具执行位置。
- Windows 本地命令优先使用原生 PowerShell，并通过 Windows Job Object 监管。
- Linux 本地命令继续使用现有兼容执行路径；Linux cgroup v2 进程监管明确延期，不在本阶段宣称强监管或确定性清理。
- 当前版本只开放本地 Agent；远程 Agent、远程登录和远程服务启动入口全部暂时关闭，后续版本重新设计后再开放。

## 2. 非目标

本阶段不实现：

- Linux cgroup v2 的本地进程监管与实机验收；
- Remote Agent + Local Executor 的分布式执行；
- 常驻 Windows Executor Service；
- Agent App Server / GUI / IDE 协议；
- 自动回滚取消前已发生的文件、Git、数据库或网络副作用。

## 3. 目标调用链

```text
clix
  -> Main
  -> Agent / AgentOrchestrator / PlanExecuteAgent
  -> HitlToolRegistry / ToolRegistry
  -> PlatformCommandRunner（Windows 本地模式启用）
  -> ActiveProcessSupervisor
  -> WindowsJobObjectContainment
  -> PowerShell / 本地文件系统
```

Linux 本地模式：

```text
clix
  -> Main
  -> Agent Runtime
  -> ToolRegistry
  -> 现有 ProcessBuilder 兼容执行路径
```

Linux 路径在 V1.3 不报告 cgroup 强监管；后续版本再引入并验收 `CgroupV2Containment`。

## 4. 入口语义

- `clix`：通过安装目录中的 `clix.cmd` 启动本地 Agent。
- `clix remote`、`clix login`、`clix serve --remote`：本阶段明确不可用，统一提示远程功能尚未开放。
- `clix app-server`：未来可选能力，本阶段不交付。

Windows 当前只使用 `clix.cmd` 启动本地 JAR；启动脚本调用安装目录内置的 Java Runtime，不要求用户安装 JDK、Maven、CMake 或 C/C++ 编译器。原生 C 启动器本阶段不参与构建和安装。

## 5. Windows 本地进程监管

- `CapabilityDetector.detectLocalWindows()` 不探测 WSL，不将 Windows 本地命令误判成 WSL Linux 命令。
- 默认 Shell 为 `pwsh.exe`（如果可用），否则使用系统 Windows PowerShell；`CLIX_POWERSHELL_EXE` 可显式覆盖。
- `WindowsJobObjectContainment` 使用 `CREATE_SUSPENDED` 创建进程，先加入设置了 `KILL_ON_JOB_CLOSE` 且禁止 breakaway 的 Job Object，再恢复线程。
- 命令 argv 必须按 Windows CRT / `CommandLineToArgvW` 规则转义；Sandbox 已生成的多参数 argv 不得重新拼成 Shell 字符串。
- 取消流程由本地 Turn 调用 `ActiveProcessSupervisor`，并以 Job Object 进程域为空作为确认依据。
- 如果 Job Object 不可用，默认明确显示兼容模式提示；设置 `CLIX_STRICT_PROCESS_CONTAINMENT=true` 时拒绝以无强监管能力启动本地 Windows CLI。

## 6. Linux 本地执行（监管延期）

- Linux Agent 与工具仍在本机执行。
- 本阶段不调用 `CapabilityDetector.detect()` 来强制建立 systemd scope。
- 现有 ToolRegistry 兼容执行路径暂时保留。
- UI/启动说明必须明确：Linux cgroup v2 监管尚未交付，不能声称 `CANCELLED_CONFIRMED` 具有强监管域证明。
- 后续扩展时复用 `ProcessContainment`、`ActiveProcessSupervisor` 和 `CgroupV2Containment`，不改变 Agent Core 与 Tool Registry 的上层调用契约。

## 7. 取消行为

- 每个本地 Agent Turn 生成独立 `turnId`，传给命令监督器。
- 每次工具调用在执行线程上绑定 `toolCallId`，执行结束后清除 ThreadLocal。
- 第一次取消设置 `CancellationContext`，取消当前 Turn 的命令监管域，再中断 Agent worker。
- Windows Job Object 的协作信号失败时，监督器仍继续尝试更强的终止阶段；不能因 CTRL_BREAK 不可用而跳过 Job Object 强制终止。
- 本阶段接通第一次 Ctrl+C / ESC 取消；本地第二次 Ctrl+C 的独立“立即强制”语义仍需后续阶段补齐，不作为已交付能力。
- 取消结果只有在监管器确认域为空时才能报告确认成功。Linux 本阶段的兼容路径不具备这一强保证。

## 8. 兼容性与迁移原则

- 远程 Java 实现暂时保留在源码中以便后续恢复，但不注册为 CLI 命令、不进入当前安装包的使用流程；本阶段不验收远程功能。
- 不改变 Agent Core、ReAct、Plan-and-Execute、Multi-Agent 的算法实现。
- 共享 `ToolRegistry`、`PlatformCommandRunner`、`ActiveProcessSupervisor` 和 `ProcessContainment`。
- 保留既有 67 项远程取消专项测试作为回归基线；新增本地 ToolRegistry 生命周期和 Windows argv 包装测试。
- 本阶段没有完成 Windows 原生主机实测前，不将 Windows Job Object 宣称为已通过真实键盘和多级子进程验收。

## 9. 验收清单

### 自动化

- [ ] `ToolRegistry` 为本地 Turn 调用 `PlatformCommandRunner.beginTurn`。
- [ ] 每个工具调用正确绑定并清除 `toolCallId`。
- [ ] 项目根目录变化同步到 Runner。
- [ ] Windows PowerShell 参数包装正确。
- [ ] Windows argv 中空格、引号、反斜杠和尾随反斜杠正确转义。
- [ ] 协作信号失败后仍升级到强制终止。
- [ ] 原有远程专项测试无回归。

### Windows 实机

- [ ] `clix.cmd` 通过内置 Java Runtime 启动本地 Agent，不要求远程登录或用户预装 Java。
- [ ] `clix remote`、`clix login`、`clix serve --remote` 均被明确拒绝，不会启动远程服务或连接远程 Agent。
- [ ] `execute_command` 实际运行在 Windows PowerShell。
- [ ] 命令进程在启动后立即归属 Job Object。
- [ ] 第一次 Ctrl+C 取消当前 Turn，Job Object 中的进程域清空。
- [ ] 第二次 Ctrl+C 立即强制清理（后续阶段）。
- [ ] 多级子进程、管道、带空格路径均可正确执行和取消。
- [ ] 取消后同一 CLI 会话可继续对话。

### Linux 实机（本阶段只验本地执行，不验强监管）

- [ ] `clix` 在 Linux 本地运行 Agent 和本地工具。
- [ ] 工具执行不依赖 Remote Agent Server。
- [ ] 启动提示明确说明 Linux cgroup 监管延期。


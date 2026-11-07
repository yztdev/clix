# V1 开发文档：连接与本地工具

状态：开发中，尚未交付。范围和用户体验以 [首版交付方案](remote-agent-v1-delivery-plan.md) 为准；当前任务取消与会话续接按 [专项开发文档](remote-agent-cancellation-session-continuity-development.md) 实施。前置条件是确定服务器地址、用户凭证发放方式，并处理影响 Windows 本地工具的已知测试问题。

已实现基础工具转发、凭证校验、监听本机的 WebSocket 连接、隔离的 Agent 记忆、会话消息和模拟模型的文件读写链路，并提供 Windows 安装脚本。远程客户端可用 `/history` 列出当前账号最近的会话编号，`/history <编号>` 查看该会话的用户和助手文字；不会恢复正在执行的任务。Windows `clix login` 先连接验证，再使用 Windows 当前用户加密保存凭证；`/mode auto|ask` 切换命令自动审查和人工确认。`serve --remote` 和 `remote` 仍是开发入口：Ctrl+C 会请求取消、停止本地工具并断开，需在真实 Windows 终端和人工确认期间手工验证；安装脚本需要预装 Java 17，尚未进行真实服务器验收。

开发入口需要服务端设置 `CLIX_REMOTE_USERS_FILE`（指向用户 ID 到凭证的 JSON 文件）和模型供应商密钥，再运行 `java -jar target/clix-1.0-SNAPSHOT.jar serve --remote`。客户端可以运行 `clix login`，或通过 `CLIX_REMOTE_URL` 和 `CLIX_REMOTE_TOKEN` 指定连接；除本机测试外必须使用 `wss` 和支持 WebSocket 的 TLS 反向代理。Windows 安装脚本为 `tools/install-clix-windows.ps1`，会复制 jar 并把安装目录加入用户 PATH。Windows 使用系统 WSL bash 时，远程客户端在独立进程组内执行命令，取消时尝试终止该进程组；命令的输入连接断开时也会尝试终止。需要 WSL 中有 `setsid`、`base64` 和 `/bin/kill`。取消不能保证结束主动脱离进程组的程序，已发生的修改不回滚，状态不明时用户需检查项目。

## 交付目标

Windows 用户在项目目录启动 `clix`，连接 Linux 上的服务；默认 Agent 在服务端处理任务，通过客户端读取、搜索、修改本地文件并运行命令。支持流式输出、登录、确认和取消。每个用户只能访问自己的会话；不支持 `/plan`、`/team`、MCP、Skill、浏览器和完整会话恢复。

## 开发任务

1. **工具执行入口**：在 `tool/ToolRegistry` 和 `tool/TurnToolPolicy` 附近引入可注入的执行方式。默认 Agent 使用既有 `executeTools()` 语义，远程执行由服务端发请求、本地返回结果；保留参数校验、结果顺序和 `onPolicyToolResults`。本地模式继续走现有实现。
2. **服务端连接**：新增远程服务入口、身份验证、会话与连接绑定，以及带版本号的双向消息。使用 HTTPS 和 WebSocket（加密的双向连接）；反向代理终止 TLS，程序默认监听本机。现有 `runtime/api` 仅作参考，不直接宣称具备长连接和本地工具功能。
3. **消息与状态**：定义 `session.start`、`user.message`、`message.delta`、`tool.request`、`tool.result`、`approval.request`、`approval.result`、`turn.cancel`、`turn.completed` 和 `turn.failed`。工具请求包含会话、任务、请求 ID、名称和参数；服务器只接受属于当前用户及请求的结果。每个请求记录接收、执行、结束或状态不明。
4. **本地执行**：复用文件工具、`ToolArgumentValidator`、`PathGuard`、`CommandGuard` 和确认处理；在本地固定项目根目录，解析真实路径并检查符号链接。支持 `read_file`、`list_dir`、`glob_files`、`grep_code`、`write_file`、`edit_file`、`execute_command`。不支持的工具明确返回错误。
5. **输出与确认**：服务端输出转成事件，本地使用 `Renderer` 显示。`auto` 审查由服务端现有逻辑完成，失败不放行；人工确认在客户端完成，`ask` 按现有规则执行。安全策略拒绝后不可通过确认绕过。
6. **断线与取消**：连接断开不启动新工具；结果不明的写入或命令不可自动重放。取消传递到服务端 Agent 和本地命令；回报真实终止结果，已完成写入不自动撤销。
7. **发布**：提供 Windows 安装与登录方式、服务端 Linux 配置和启动说明；API Key 存服务器，客户端凭证不进入仓库或日志。明确旧配置和旧数据的处理，禁止静默覆盖。

## 测试与完成条件

- 单元测试：消息编码、版本与身份校验、请求归属、参数错误、相对路径和符号链接越界、确认拒绝、超时和取消。
- 集成测试：模拟模型与本地客户端，验证读取→修改→运行命令的消息链、输出顺序、策略拒绝及不重复执行。不得调用真实模型。
- 故障测试：分别在接收前、执行中、结果发送前断线；确认有副作用的请求不会自动重放，状态不明可见。
- 回归：原本地模式的命令解析、Agent、工具、安全和渲染测试；记录 Windows 既有失败，比较失败名单。
- 人工验收：在真实 Windows 项目和 Linux 服务上验证安装、登录、中文输出、确认、取消及断线。真实模型调用须另行安排并获得明确同意。

交付物包括服务端程序、Windows 客户端、安装配置文档和测试结果；用户能够在本地目录完成一次模拟模型驱动的文件修改与命令执行。

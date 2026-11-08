> 当前状态（2026-11-08）：本文是远程 Agent 模式的历史专项设计。当前 Local-first 版本暂时关闭远程启动、远程登录和远程客户端 CLI 入口；本文中的远程实机验收不属于当前本地版本验收范围。

- # 远程 Agent 当前任务取消与会话续接开发文档

  > 文档版本：V1.2（四项审查意见闭环修订稿）\
  > 修订原则：保留 V1 原有取消与进程监管设计；本文件末尾的 V1.2
  > 规范性约束优先于前文冲突表述。

  状态：阶段一、阶段二、阶段三、V1.2 G（I-15~19）已实现并单测；阶段四 Java 侧（带外 Ctrl+C 通道 `ConsoleCancelBridge` + 退出时活动记录核查）已实现并单测，原生 `clix.exe` 源码已编写（本环境无 C 编译器，待 Windows 下构建）；真实 Windows 键盘 / WSL 端到端实机验收（完成条件 12）尚未通过。本文是 V1 远程模式的取消专项设计，优先级高于当前"Ctrl+C 取消并断开"行为。

  > 阶段一实施进度（2026-11-06）：`turn_id` 贯穿协议消息、客户端 Turn 状态机（`IDLE → RUNNING → CANCELLING → CANCELLED/COMPLETED → IDLE`）与不可延长的 10 秒绝对截止时间；服务端按不晚于客户端的预算（默认 8 秒）终态化旧 Turn、支持 `force` 二次 Ctrl+C、按 `turn_id` 隔离迟到输出，`turn.cancelled` 的 `termination` / `transport_cancel_status` / `provider_cancel_ack` / `workspace_guard` 改为真实观测而非写死常量；客户端删除连接级永久取消语义，维护连接状态、终态 Turn 集合和迟到事件计数，拆分 `cancelTurn(turnId)` / `closeConnection()`，并让等待中的 HITL 审批可被取消打断；本地解析 `/status`、`/continue`、`/retry`、`/quarantine`，实现最小工作区安全门和一次性中断说明注入。取消纯模型请求后不重新连接即可继续对话，已由 `RemoteClientConnectionTest`、`RemoteAgentCancellationTest`、`RemoteServerTerminalizationTest` 覆盖。
  >
  > 阶段二实施进度（2026-11-06）：新增 `com.clix.process` 包，`ActiveProcessSupervisor` 成为远程客户端所有本地命令的生命周期所有者——活动记录在创建进程前登记为 `STARTING`，取消与启动竞态时的新进程由 `markRunning()` 立即终止；分级 `INT(2s) → TERM(3s) → KILL + 空域确认(剩余最多 5s)` 受不可延长的 10 秒绝对截止时间约束；只有 nonce 与 PID start-time 校验通过、且监管域被观测为 `EMPTY` 才返回 `CANCELLED_CONFIRMED`，否则为 `CANCELLED_UNKNOWN`。平台实现：`CgroupV2Containment`（transient `systemd-run --user --scope`，`cgroup.procs` 空且 `cgroup.events` 的 `populated 0` 才算空域）、`WindowsJobObjectContainment`（JNA `CREATE_SUSPENDED` → 加入禁止 breakaway 且 `KILL_ON_JOB_CLOSE` 的 Job → `ResumeThread`）、`PgidOnlyContainment`（只向登记的 PGID 发组信号，不追 PPID 链、不杀疑似后代，检测到二次 `setsid` 直接记 UNKNOWN）。`CapabilityDetector` 做真实能力探测，严格模式拒绝无强监管域启动，兼容模式明确标记 `PGID_ONLY`；由于当前远程客户端的命令方言是 bash（由 `where.exe bash` 解析），Windows Job Object 只在 `-Dclix.remote.nativeWindowsJobObject=true` 的纯原生命令场景启用，避免静默更换启动器改变既有命令语义。`ToolRegistry` 抽出 `PlatformCommandRunner`，远程命令全部走监督器，本地模式保持原路径。`RecoveryInspector` 在隔离后最多后台诊断 60 秒，确认空域才把工作区自动恢复 `SAFE`；`/status` 显示真实监管域级别与未确认 operation 数。
  >
  > 阶段二验收：`ActiveProcessSupervisorTest` / `ProcessContainmentTest` / `RemotePhaseTwoIntegrationTest` 共 28 项通过，覆盖 STARTING 竞态、graceful/forced/unknown 终态、nonce 与 PID 复用、二次 setsid、10 秒不可延长、cgroup 空域双条件、PGID_ONLY 不追杀、60 秒诊断截止与自动恢复 SAFE。**限制**：本机安全策略禁止启动 `wsl.exe`，因此 WSL systemd/cgroup、Windows Job Object 的真实进程启动以及真实键盘 Ctrl+C 端到端验收尚未在此环境实机验证；相关平台实现以可注入 `ProcessContainment` 的假监管域完成逻辑验收。
  >
  > 阶段三实施进度（2026-11-06）：新增 `com.clix.effect` 包。`EffectRecorder` 把「规范化路径 → before 状态/SHA-256 → 工具执行 → after 状态/SHA-256 → JSONL 追加并 force flush」包在同一个 operation 锁内，执行前先落 `effect_started`；审计写入失败、路径越界或哈希采集失败一律降级，绝不伪报 `verified`。`outcome`（completed/failed/cancelled_unknown）与 `effect_audit` 完全独立，命令工具的 `termination` 取自 `ActiveProcessSupervisor` 的真实终态。命令观察走 `CommandEffectProbe`：Git 项目只采 HEAD + porcelain 前后差集（默认 `partial`），非 Git 且不声明 `output_paths` 时为 `unavailable` 且不启动任何推断式扫描；`output_paths` 已加入 `execute_command` 的参数 schema；命令摘要脱敏、截断并只保留 `command_digest`。`tool.effect` 携带 `sequence` / `content_hash` / `schema_version`，服务端 `EffectIngestor` 按 `turn_id + tool_call_id + operation_id` 幂等，同键异 hash 追加 `effect_conflict`、同 sequence 异 hash 追加 `sequence_conflict`，乱序版本全部保留且不关闭连接；`/retry` 复用旧 operation_id 会被客户端与服务端同时拒绝。`ConversationLedger` 追加 `turn_started` / `tool_terminalized` / `tool.effect` / `turn_cancelled` / `interruption_context_attempt` / `interruption_context_injected`；`InterruptionInjector` 只在收到首个模型流 delta 或请求返回后才写注入事件，崩溃窗口允许带「忽略重复说明」提示再注入一次，同一 source 只绑定一个 target。`InterruptionContextBuilder` 只从结构化事实生成固定格式说明（已完成操作、受影响路径、被中断工具、冲突/版本数、未知风险与 V1.2 C 硬约束），通过 `Agent.setExternalContextSupplier` 作为请求级上下文注入，不进 `agentHistory`；客户端 `/continue`、`/retry`、`/status` 同步改用同一份结构化事实。`effect_started` 无终态时重启会补交 `unavailable` 摘要但不重放工具。
  >
  > 阶段三验收：`com.clix.effect.*` 与 `RemotePhaseThreeIntegrationTest` 共 28 项通过；远程 / process / effect 全量 110 项中 105 项通过，5 项失败全部为 `wsl.exe` 被本机安全策略禁止与真实 Windows 控制台 Ctrl+C 造成的既有环境项；全库 `mvn -Pquick test` 的失败方法名单与阶段二基线逐条一致（新增 0、消失 0）。**限制**：V1.2 G 的 Provider Adapter per-turn chunk gate 已实现为 `com.clix.llm.ProviderOutputGate`（流式边界第一道拦截，`RemoteAgentSession` 保留 `TurnOutputGate` 作为第二道防线），I-15~19 验收测试已补齐；阶段四原生 `clix.exe` 与真实 WSL / 键盘端到端验收仍未在此环境实机验证。
  >
  > 仍待实现（代码层）：原生 `clix.exe` 的 Windows 构建与真实键盘 Ctrl+C 验收。Java 侧带外取消通道与退出活动记录核查已实现并通过 `ConsoleCancelBridgeTest`；因本沙箱无 C 编译器且 `wsl.exe` 被禁用，代码完成不影响判断，但真实 Windows 控制台 / WSL 端到端验收（阶段四与完成条件 12）仍必须通过实机，未通过即视为未完成。

  本方案只承诺三件事：第一，取消后让当前任务拥有的本地前台执行域确定地停止，或者明确报告无法确认；第二，只结束当前
  Turn，不关闭
  WebSocket、登录态和会话历史；第三，把取消前已经完成的操作、观察到的文件变化和未知风险写入下一轮上下文，让模型能够继续工作。

  本方案不自动回滚文件或命令副作用。进程已经停止不代表工作区一定完整；续接能力的含义是"已知变化可见、未知风险明确、下一轮先核验再行动"，不是自动恢复到取消前状态。

  ## 目标和非目标

  ### 交付目标

  -   Ctrl+C 只取消当前 Turn，取消完成后连接仍可复用。
  -   取消同时覆盖服务端模型请求、服务端 Agent
      循环、本地审批和本地工具执行。
  -   前台 `execute_command`
      在独立监管域中运行，取消时先协作式中断，再强制清理并确认监管域为空。
  -   原生文件工具记录具体路径、操作类型、执行结果和修改前后哈希。
  -   Shell 命令记录命令摘要、终止结果、可观察文件变化和外部副作用风险。
  -   下一轮上下文包含上一轮目标、已完成步骤、中断位置、观察到的变化和未知范围。
  -   中断工具不会自动重放；新的有副作用操作重新经过策略和审批。
  -   真实 Windows 键盘 Ctrl+C、WSL、多级子进程和原生启动入口都纳入验收。

  ### 非目标

  -   不自动回滚取消前已经发生的文件写入、Git 操作、数据库提交或网络请求。
  -   不保存专门用于取消回滚的文件前镜像，不提供 `/rollback` 命令。
  -   不承诺完整识别任意 Shell 命令的全部副作用。
  -   不自动重放状态不明或被中断的工具调用。
  -   V1 不提供显式后台任务；所有远程 `execute_command` 都按前台任务处理。
  -   不依赖模型在取消时生成总结；模型不可用时仍要形成中断上下文。
  -   本期不交付跨设备会话恢复界面；只保证同一远程会话可继续，并为后续恢复保存事件。

  现有 Side-Git 和 `revert_turn`
  继续作为独立功能存在，但取消流程不自动调用，也不以它们作为取消成功条件。

  ## 用户体验

  命令运行中第一次按 Ctrl+C：

  ``` text
  正在取消当前任务…
    ✓ 已停止服务端 Agent
    ✓ 已确认本地命令监管域静止
    ✓ 已保存中断位置和观察到的变化
  
  当前任务已取消，会话仍保持连接。
  工作区可能包含部分修改；继续时会先显示核验信息。
  可继续输入，或使用 /continue、/status、/retry。
  >
  ```

  各场景语义如下：

    场景                  Ctrl+C 行为

  --------------------- ------------------------------------------------

    Turn 正在运行         取消当前 Turn，不退出客户端
    HITL 正在等待         拒绝本次审批并取消当前 Turn
    正在输入且没有 Turn   清空当前输入
    输入为空且没有 Turn   第一次提示再次按键退出，限定时间内第二次才退出
    正在取消              第二次按键跳过剩余协作窗口，直接进入强制清理

  客户端只在
  `/exit`、`/quit`、认证失效、协议错误、网络不可恢复断开或明确的二次退出操作时关闭连接。

  ## 状态模型

  ### 连接状态

  ``` text
  CONNECTING → OPEN → CLOSING → CLOSED
  ```

  Turn 取消不能把连接从 `OPEN` 变成 `CLOSING`。连接对象不得使用永久
  connection-level `cancelled` 标志阻止后续消息。

  ### Turn 状态

  ``` text
  IDLE → RUNNING → CANCELLING → CANCELLED → IDLE
  IDLE → RUNNING → COMPLETED → IDLE
  ```

  同一会话同一时间只允许一个前台
  Turn。所有用户消息、工具请求、工具结果和取消消息都携带 `turn_id`；旧
  Turn 的迟到消息不得进入新 Turn。

  ### 工具状态

  ``` text
  SCHEDULED
    → APPROVAL_PENDING
    → RUNNING
    → CANCELLING
    → CANCELLED_CONFIRMED | CANCELLED_UNKNOWN
  ```

  其他终态为 `COMPLETED`、`REJECTED`、`FAILED` 和 `TIMED_OUT`。

  -   `CANCELLED_CONFIRMED`：已确认工具拥有的监管域为空。
  -   `CANCELLED_UNKNOWN`：取消已经发出，但无法确认监管域是否静止。
  -   `COMPLETED`：取消到达前工具已经完成，不能把它改写成取消成功。

  工具生命周期与副作用观察分开记录：

  ``` text
  termination: graceful | forced | not_required | unknown
  containment: cgroup | job_object | pgid_only | none
  effect_audit: verified | partial | unavailable
  ```

  `CANCELLED_CONFIRMED + forced + cgroup + partial`
  表示进程已被强制清理，但只能观察到部分副作用。界面不得把它显示为"操作已撤销"或"工作区已恢复"。

  ### 工作区安全状态

  ``` text
  SAFE → QUIESCING → SAFE
                 ↘ QUARANTINED_UNKNOWN
                      ├→ SAFE（后续确认监管域为空）
                      └→ FORCED_RELEASED（用户知情解除）→ SAFE（后续核验通过）
  ```

  取消达到 10 秒硬上限但仍为 `CANCELLED_UNKNOWN`
  时，客户端恢复提示符并保持连接，但把当前项目置为
  `QUARANTINED_UNKNOWN`。用户可以继续对话、查看 `/status`
  和执行只读核验；新的文件写入和 `execute_command`
  暂时拒绝，避免未知旧进程与新 Turn 竞争。

  后台诊断从进入隔离态开始最多运行 60 秒，单次探测也必须有超时。60
  秒内确认监管域为空则自动回到
  `SAFE`；到期仍无法确认时停止自动诊断，并提供
  `/quarantine unlock --force`。

  强制解除采用两步确认。第一次只展示未确认
  operation、最后一次探测结果和风险范围，并生成绑定项目、隔离版本和本地用户的一次性确认短语；短语
  60 秒后失效。用户再次输入完整短语后，状态进入
  `FORCED_RELEASED`，而不是伪装成 `SAFE`。该操作不能被模型或 `auto`
  模式批准，并写入会话记录。

  ## 取消协调流程

  Ctrl+C 触发由 `turn_id` 标识的结构化取消，不直接关闭连接：

  ``` text
  1. 客户端把当前 Turn 原子地改为 CANCELLING。
  2. 本地工具监督器拒绝该 Turn 的新工具和迟到审批。
  3. 客户端立即向服务端发送 turn.cancel。
  4. 服务端取消模型 HTTP 请求、Agent runner 和 pending tool future。
  5. 客户端并行取消本地活动工具。
  6. 本地执行域静止后，客户端完成本轮副作用摘要。
  7. 客户端发送工具终态和副作用摘要。
  8. 服务端把 turn_cancelled 和 effect_report 追加到会话记录。
  9. 服务端发送 turn.cancelled，客户端清空当前 Turn 并回到提示符。
  ```

  副作用收集属于取消收尾，但不能突破取消硬上限。到达截止时间时仍未完成的扫描标为
  `effect_audit=partial` 或 `unavailable`，不能继续阻塞输入线程。

  如果网络在取消期间断开，本地进程清理仍要继续。客户端把尚未发送的摘要保存在当前用户专属的本地待发送记录中，按
  `turn_id + tool_call_id` 幂等补交；补交只发送事实记录，绝不重放原工具。

  ## 取消时间预算

  第一次 Ctrl+C 创建不可延长的绝对截止时间：

  ``` text
  cancel_deadline = first_ctrl_c + 10s
  ```

  默认阶段预算：

    阶段                           最长时间

  --------------------------- ---------------

    SIGINT 协作退出                  2 秒
    SIGTERM 协作退出                 3 秒
    SIGKILL、监管域清理和确认    剩余最多 5 秒

  工具配置只能缩短某一阶段，不能延长 10 秒全局上限。第二次 Ctrl+C
  立即进入强制清理，但不重置截止时间。

  服务端收到取消后最多使用 8 秒终态化逻辑 Agent
  run，为网络传输和客户端收尾预留约 2
  秒。即使供应商连接没有确认关闭，服务端也要隔离旧 `turn_id`
  的迟到输出并允许会话继续。

  ## 活动进程监督器

  新增
  `ActiveProcessSupervisor`，作为远程客户端所有本地命令的生命周期所有者。它不能依赖执行命令的线程是否收到
  `Thread.interrupt()`。

  每条命令在启动前登记：

  ``` text
  operation_id
  turn_id
  tool_call_id
  project_root
  command_digest
  platform
  Windows process handle / PID
  WSL distribution
  Linux PID / PGID / SID
  cgroup path / systemd unit
  containment level
  随机 nonce
  started_at
  state
  ```

  监督器提供：

  ``` java
  registerStarting(...)
  markRunning(...)
  cancelTurn(turnId)
  terminate(operationId)
  confirmExited(operationId)
  complete(operationId, exitCode)
  ```

  活动记录必须在命令启动前创建为
  `STARTING`。取消与启动并发时，启动路径看到 Turn 已进入
  `CANCELLING`，必须立即终止刚创建的进程，不能漏过"已经审批但尚未登记
  PID"的窗口。

  ## Linux 和 WSL 执行域

  ### 强监管模式

  PGID 不能覆盖主动二次 `setsid`
  的进程。生产远程模式优先使用可继承的监管域：

  -   Linux 和支持 systemd/cgroup v2 的 WSL：每个前台 operation 创建独立
      transient systemd unit 或受控 cgroup。
  -   子进程创建新 session、PGID、`nohup` 或 Shell 后台任务时，仍继承原
      cgroup。
  -   监管域由 systemd 或最小权限 helper 持有；被执行命令不能迁出 clix
      监管树。
  -   取消完成时确认 `cgroup.procs` 为空，且 `cgroup.events` 的
      `populated` 为 `0`。

  启动时执行能力探测并记录 `containment`。生产严格模式要求
  `cgroup`；无法建立时只能进入明确标记的 `PGID_ONLY`
  兼容模式，不能宣称确定性清理。

  ### 启动和终止

  推荐通过用户级 transient service 启动命令，并保留 systemd
  unit、cgroup、PGID、operation ID 和 nonce。协作退出依次向受管进程发送
  SIGINT 和 SIGTERM；仍存活时使用 SIGKILL 和 `cgroup.kill` 或等价 systemd
  操作。

  只有确认监管域为空才能返回 `CANCELLED_CONFIRMED`。如果进程在 INT 或 TERM
  阶段已经全部退出，无需发送多余 SIGKILL，但仍要完成空域确认。

  ### PGID_ONLY 降级模式

  降级模式只向已登记并通过 PID start time、operation nonce 和所有权校验的
  PGID 发送组信号。对 SID 的补充处理只检查 SID
  完全相等且身份可验证的成员；不沿 PPID 链追踪，不扫描或 kill "疑似后代"。

  发现二次 `setsid`、daemonize
  或无法归属的进程时，不对它们发送信号，结果直接记为 `CANCELLED_UNKNOWN`
  并进入工作区隔离。

  ## Windows 原生命令

  普通 Windows 命令使用禁止 breakaway 且启用
  `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` 的 Job
  Object。为了避免"进程已经启动但还没加入
  Job"的竞争窗口，强保证实现不能简单地在 `ProcessBuilder.start()`
  之后再分配 Job；应通过原生 helper 或 JNA 使用暂停创建、加入
  Job、恢复线程的顺序启动。

  Job Object
  只约束正常继承的本地进程树。通过系统服务、WMI、任务计划程序、容器运行时或其他外部代理创建的任务视为外部副作用，记录
  `external_side_effects_possible=true`，不能声称已经被 Job Object 清理。

  WSL 内部进程仍以 Linux cgroup 为权威；不能假设杀掉 `bash.exe` 或关闭
  Windows Job 就会结束 Linux 子进程。

  ## 前台和后台命令

  V1 不交付后台任务。远程 `execute_command`
  全部视为前台命令，取消语义固定为终止。

  前台命令自行使用 `nohup`、`&`、`disown`、二次 `setsid` 或
  daemonize，不会获得后台任务身份；强监管模式下仍随原 operation
  一起终止。兼容模式检测到明显后台化意图时要求额外审批或拒绝，但静态命令检查不作为安全边界。

  以后如果增加
  `background=true`，必须另写后台任务专项设计，不在本方案中预留运行中迁移逻辑。

  ## 协议调整

  远程协议升级版本，所有 Turn 和工具消息显式携带
  ID。旧客户端和新服务端版本不匹配时拒绝连接并提示升级，不能静默回退到"取消并断开"。

  ### 用户消息

  ``` json
  {
    "type": "user.message",
    "payload": {
      "turn_id": "turn-uuid",
      "content": "运行测试并修复错误",
      "mode": "ask"
    }
  }
  ```

  ### 工具请求

  ``` json
  {
    "type": "tool.request",
    "payload": {
      "turn_id": "turn-uuid",
      "batch_id": "batch-uuid",
      "calls": [
        {
          "id": "tool-uuid",
          "name": "execute_command",
          "argumentsJson": "{...}"
        }
      ]
    }
  }
  ```

  ### execute_command 的受限观察参数

  `execute_command.arguments` 增加可选
  `output_paths: string[]`。缺省或空数组视为未声明；路径必须相对
  `project_root`，规范化后不得越界。该字段只声明需要观察的输出范围，不授予额外文件访问权限，也不改变命令审批级别。

  ### 取消请求

  ``` json
  {
    "type": "turn.cancel",
    "payload": {
      "turn_id": "turn-uuid",
      "reason": "user_interrupt",
      "cancel_timeout_ms": 10000
    }
  }
  ```

  客户端用本地单调时钟维护真正的绝对截止时间。协议中的 `cancel_timeout_ms`
  只用于让服务端采用不晚于客户端的预算，不能由服务端延长。

  ### 文件工具副作用摘要

  ``` json
  {
    "type": "tool.effect",
    "payload": {
      "turn_id": "turn-uuid",
      "tool_call_id": "tool-edit",
      "operation_id": "operation-uuid",
      "tool": "edit_file",
      "outcome": "completed",
      "effect_audit": "verified",
      "files": [
        {
          "path": "src/RemoteClientSession.java",
          "operation": "modify",
          "before_sha256": "sha256:...",
          "after_sha256": "sha256:..."
        }
      ]
    }
  }
  ```

  ### 命令副作用摘要

  ``` json
  {
    "type": "tool.effect",
    "payload": {
      "turn_id": "turn-uuid",
      "tool_call_id": "tool-command",
      "operation_id": "operation-uuid",
      "tool": "execute_command",
      "outcome": "cancelled_confirmed",
      "termination": "forced",
      "containment": "cgroup",
      "effect_audit": "partial",
      "command_digest": "sha256:...",
      "command_summary": "mvn test",
      "observed_paths": ["target/surefire-reports/..."],
      "external_side_effects_possible": false
    }
  }
  ```

  `tool.effect` 按 `turn_id + tool_call_id + operation_id`
  幂等。相同内容的重发忽略；不同内容追加
  `effect_conflict`，保留所有版本，不按协议错误断开连接。事件必须携带
  `sequence`、`content_hash` 和 `schema_version`。

  ### Turn 取消完成

  ``` json
  {
    "type": "turn.cancelled",
    "payload": {
      "turn_id": "turn-uuid",
      "connection_reusable": true,
      "termination": "confirmed",
      "workspace_guard": "safe",
      "workspace_state": "needs_verification",
      "effect_audit": "partial",
      "transport_cancel_status": "confirmed",
      "provider_cancel_ack": "unsupported",
      "provider_output_quarantined": true
    }
  }
  ```

  `transport_cancel_status` 描述本地 HTTP transport 是否已经结束，取值为
  `confirmed | unconfirmed | unknown`。`provider_cancel_ack`
  描述供应商是否明确确认远端任务停止，取值为
  `confirmed | unsupported | unknown`。普通 HTTP
  断连不能被写成供应商确认。

  取消后迟到的 `tool.result`、`message.delta` 和 `turn.completed` 必须按
  `turn_id` 丢弃或记录为迟到事件，不能进入下一轮。

  ## 副作用记录

  ### 记录原则

  副作用指工具已经造成或可能造成的外部变化，例如文件修改、生成构建产物、Git
  操作、数据库写入或网络请求。记录的目标是帮助下一轮正确推理，不是为自动恢复提供材料。

  所有记录遵守：

  -   只记录事实和明确的未知范围。
  -   不因为工具没有正常返回就假设它没有执行。
  -   不把"观察到的文件集合"表述为"全部副作用"。
  -   不上传完整文件正文、完整 stdout、凭证或环境变量。
  -   路径使用项目内相对路径；命令保存摘要和哈希，不保存可能含密钥的原文。
  -   每个摘要限制路径数量和总字符数，超限内容落入本地诊断文件，上下文只保留计数和引用
      ID。

  ### 原生文件工具

  `write_file`、`edit_file`、`delete_file`
  和受控重命名工具可以准确知道目标路径。工具必须在同一工具事务/路径锁作用域内完成"规范化路径
  → before 哈希 → 执行 → after 哈希 →
  审计事件追加"，不得把哈希计算放到异步事后任务。记录：

  ``` text
  path
  operation: create | modify | delete | rename
  outcome: completed | failed | cancelled_unknown
  before_sha256
  after_sha256
  bytes_written（可获得时）
  ```

  这些元数据足以告诉模型哪些文件发生过变化，但不保存用于恢复的文件内容。

  `effect_audit`
  只描述声明副作用范围的记录完整性，不描述操作成功与否，也不表示工作区一致或可回滚：

  -   `verified`：该工具声明的副作用范围已被完整记录为结构化元数据。
  -   `partial`：已记录部分范围，但仍存在未覆盖路径、采集失败或观察边界。
  -   `unavailable`：没有可用的可靠观察结果。

  原生文件工具的判定规则：

  1.  执行前先规范化目标路径并获取 `before_sha256`；不存在的文件使用明确的
      `absent` 状态，不把它与哈希失败混淆。
  2.  执行后，无论成功、失败还是取消，都尝试获取
      `after_sha256`、`outcome`、`bytes_written`（可获得时）。
  3.  当路径规范化成功，且 before/after
      状态均成功采集时，`effect_audit=verified`。工具返回 `COMPLETED` 不是
      verified 的必要条件；`FAILED` 或 `CANCELLED_UNKNOWN` 也可以
      verified，因为记录的是已知副作用范围。
  4.  任一哈希/存在性采集失败，但目标路径仍可规范化且操作对象明确时，降为
      `partial`；路径无法规范化或完全无法确定目标范围时为 `unavailable`。
  5.  文件被替换、重命名或删除时，事件必须明确记录源路径和目标路径，不能只记录一个含糊的
      path。
  6.  每个 effect 事件 append 到本地 append-only JSONL，完成 flush
      后才向上层返回审计完成。审计写入失败必须使本次 audit
      降级并留下诊断，不得伪报 verified。

  不引入前镜像存储。文件锁/事务锁必须覆盖 before 采集、工具执行、after
  采集和事件写入；如果底层文件系统无法保证该锁对外部编辑器生效，文档和事件应注明该锁只串行化本工具，不代表排除了外部并发修改。

  ### execute_command

  Shell
  命令无法可靠识别数据库、网络、容器、忽略文件或项目外路径的全部变化。观察分为
  Git 和非 Git 两条路径：

  **Git 项目：**

  -   执行前记录 `git rev-parse HEAD` 和
      `git status --porcelain`；执行后重新采集。
  -   对状态差集生成 `observed_paths`，明确这是相对 Git
      状态的可观察变化，不是全部副作用。
  -   因 Shell 是黑盒，默认 `effect_audit=partial`。
  -   只有调用参数显式声明
      `output_paths`，且这些路径的前后基线及哈希校验全部成功时，才允许把"声明路径覆盖范围"标记为
      `verified`；该 verified 只覆盖
      `output_paths`，不得扩展解释为整个命令的全部副作用已知。建议同时增加
      `audit_scope=declared_output_paths`，避免把局部 verified 误读为全局
      verified。

  **非 Git 项目：**

  -   默认
      `effect_audit=unavailable`、`observed_paths=[]`，不做事后全盘扫描、`find`、mtime
      猜测或目录遍历。
  -   参数存在 `output_paths`
      时，只对这些路径做执行前/后的受限采集：存在性、mtime、size；小文件可选
      SHA-256。路径必须在项目根目录内规范化，拒绝 `..`
      逃逸、符号链接越界和重复路径。
  -   由于只覆盖调用方声明的范围，`effect_audit=partial`，即使声明路径采集完整也不升级为全局
      verified。
  -   未提供 `output_paths`
      时明确拒绝启动任何推断式扫描；中断上下文必须写明"未观察文件变化；请手工检查"。

  `output_paths` 是工具参数的一部分，示例：

  ``` json
  {
    "command": "mvn test",
    "output_paths": ["target/surefire-reports/", "target/classes/"]
  }
  ```

  无论 Git
  与否，都保存经过脱敏和截断的命令摘要与哈希、退出码或终止方式。命令包含网络、数据库、Docker、Git
  push 或外部任务入口时，记录 `external_side_effects_possible=true`。

  现有 Side-Git
  可以为差异计算提供元数据，但取消流程不创建专门前镜像、不自动恢复，也不把
  Side-Git 可用性当作取消成功条件。

  ### 本地待发送记录

  客户端在执行有副作用工具前写入最小 `effect_started`
  记录，完成后追加实际结果。记录采用当前用户专属权限和追加写入，防止客户端在工具执行后、摘要发送前崩溃时完全丢失事实。

  本地记录只保存结构化元数据，默认保留 30 天并设置容量上限。会话恢复时按
  ID
  补交未确认摘要；补交不会启动工具，也不会把过期摘要直接写进一个已经运行的新
  Turn。

  ## 中断上下文

  ### 会话记录

  `ConversationLedger` 是项目已有的追加式会话记录。取消至少追加：

  ``` json
  {
    "event": "turn_cancelled",
    "turn_id": "turn-uuid",
    "user_message": "运行测试并修复错误",
    "stage": "tool_execution",
    "termination": "confirmed",
    "transport_cancel_status": "confirmed",
    "provider_cancel_ack": "unsupported",
    "completed_tools": [
      {
        "name": "edit_file",
        "affected_paths": ["src/RemoteClientSession.java"]
      }
    ],
    "interrupted_tool": {
      "name": "execute_command",
      "command_summary": "mvn test",
      "effect_audit": "partial",
      "observed_paths": ["target/surefire-reports/..."]
    },
    "external_side_effects_possible": false
  }
  ```

  记录只追加，不覆盖旧事件。服务端不能因为工具没有正常返回就删除已经保存的
  assistant tool call 或文件变化摘要。

  ### 写入下一轮上下文

  取消路径不调用模型生成摘要。下一条用户消息到达时，服务端根据会话记录确定性生成一次中断说明：

  ``` text
  [CLIX_INTERRUPTED_TURN_CONTEXT]
  上一轮任务被用户取消。
  
  原始目标：运行测试并修复错误
  已经完成：读取配置；修改 src/RemoteClientSession.java
  被中断：execute_command: mvn test
  终止结果：本地 cgroup 已确认清空；命令以 SIGKILL 结束
  模型请求：本地 HTTP transport 已结束；供应商不支持远端取消确认
  观察到的变化：
  - modify src/RemoteClientSession.java
  - create target/surefire-reports/...
  未知风险：Shell 命令可能还有未被项目观察器覆盖的副作用
  
  继续前重新读取相关文件和 git diff。
  不要假设被中断命令已成功，也不要自动重跑有副作用工具。
  [/CLIX_INTERRUPTED_TURN_CONTEXT]
  ```

  该内容是 clix
  根据结构化记录生成的系统上下文，不包含原始命令输出，也不接受工具输出中的指令。它不作为
  assistant 消息或持久化的模型正文写入 agentHistory。服务端在下一条
  user.message 到达后，从 Ledger
  确定性构建上下文；仅当请求组装并成功发送给模型（收到响应头/流开始确认）后，才追加
  `interruption_context_injected`
  事件。若发送后、事件落盘前崩溃，重启后允许再次注入一次；上下文开头提示模型识别重复说明并以最新状态为准。若注入事件已落盘但模型响应前崩溃，恢复时不向已开始的新
  Turn 补发，也不串接到其他 Turn；Ledger 中的取消事实仍可由 `/status`
  查看。

  模型开始新的文件修改前必须重新读取目标文件，因为用户、IDE
  或其他进程可能在取消后再次修改工作区。

  ### 命令语义

  -   `/continue`：创建新
      Turn，注入上次中断说明后继续推理，不自动执行旧工具。
  -   `/retry`：只创建新 Turn，并重新提交上一轮原始用户目标；绝不重放旧
      `tool.request`、工具参数、审批结果或
      operation。模型根据中断上下文重新规划，任何工具调用都必须重新生成
      `tool.request` 并经过完整 ask/auto/deny 策略。
  -   `/status`：显示上一
      Turn、活动监管域、观察到的变化、未知风险和工作区安全状态。
  -   普通自然语言输入：默认保留同一会话历史，并在首次输入时注入尚未消费的中断说明。

  ## 组件改造

  ### `RemoteClientCommand`

  -   Ctrl+C 不再设置连接级 failure，也不让主输入循环退出。
  -   新增 `CurrentTurnController` 管理 Turn 状态和 10 秒截止时间。
  -   等待 `turn.cancelled`
      或绝对截止时间，以先到者恢复提示符；超时进入隔离态。
  -   `/continue`、`/status`、`/retry`
      和隔离解除由本地命令解析，不交给模型猜测。

  ### `RemoteClientConnection`

  -   删除永久阻止后续发送的连接级 `cancelled` 语义。
  -   维护 `activeTurnId`、已终态 Turn 集合和连接状态。
  -   `cancelTurn(turnId)` 幂等，但不能关闭 WebSocket 或本地工具
      executor。
  -   按 `turn_id` 隔离迟到事件。

  ### `RemoteClientSession`

  -   把 `close()` 拆成 `cancelTurn(turnId)` 和 `closeConnection()`。
  -   worker 是连接级资源；每个 Turn 使用独立任务句柄和取消状态。
  -   取消只停止目标 Turn 的工具，新 Turn 重新建立请求跟踪范围。
  -   审批返回后再次检查 Turn 状态，防止取消后执行。
  -   `FORCED_RELEASED` 只解除工作区安全门，不得改变工具 `ask/auto/deny`
      决策；每次执行仍校验 `turn_id`、`tool_call_id`、`operation_id`
      的新鲜性。

  ### `RemoteLocalTools` 和 `RemoteClientToolRegistry`

  -   所有工具绑定 `turn_id`、`tool_call_id` 和 `operation_id`。
  -   原生文件工具在执行前后生成精确文件摘要。
  -   `execute_command` 调用 `ActiveProcessSupervisor`
      并生成部分副作用摘要。
  -   本地待发送记录在工具启动前写入，不能等正常返回后才登记。
  -   工作区处于隔离态时，只允许固定的只读核验器，禁止任意 Shell。

  ### `ToolRegistry`

  -   抽出平台进程启动和终止接口，不再使用永久
      `activeCommandCancellation`。
  -   超时和用户取消共用分级终止实现，但记录不同 outcome。
  -   命令观察器返回结构化摘要，不只记录 stdout 哈希。

  ### `RemoteAgentSession`

  -   `turn.cancel` 只结束当前 Agent run，不关闭远程 session。
  -   模型取消状态改成单请求作用域，不能污染下一 Turn。
  -   服务端在 8 秒内发送 `turn.cancelled`；未确认项如实标记 unknown。
  -   供应商迟到输出必须先在 Provider Adapter 的 per-turn chunk gate
      过滤，RemoteAgentSession 的 turn_id 检查作为第二道防线。
  -   完成后允许同一 WebSocket 接收下一条 `user.message`。

  ### `ConversationLedger`

  -   增加 Turn 生命周期、工具终态、副作用摘要和中断上下文注入事件。
  -   保存相对路径、操作类型、哈希、命令摘要和未知范围，不保存完整文件内容。
  -   跨天恢复仍能区分本地 transport 取消与供应商远端确认。

  ### `InterruptionContextBuilder`

  -   从结构化事件生成固定格式中断说明，不调用模型。
  -   对路径、摘要、数量和字符长度做上限控制。
  -   不复制原始 stdout、网页正文或工具返回中的指令。
  -   生成内容包含事实、未知风险和下一步核验要求。

  ### Windows `NativeLauncher`

  -   `clix.exe` 使用 `SetConsoleCtrlHandler` 接管 `CTRL_C_EVENT`。
  -   handler 只执行无阻塞事件投递，例如设置预创建 Win32 Event
      或写入预分配队列。
  -   禁止在 handler 内分配大量内存、获取可能阻塞的锁、访问网络、等待 JVM
      或执行进程清理。
  -   入队成功后返回 `TRUE`；入队失败时返回 `FALSE`，允许后续/default
      handler 接管。
  -   真正的 WebSocket 消息、状态转换和工具终止由普通工作线程完成。

  ## 并发和安全规则

  -   Ctrl+C、工具完成和连接断开可能同时发生；状态转换使用 CAS
      或等价原子操作。
  -   `RUNNING → COMPLETED` 与 `RUNNING → CANCELLING` 只能有一个成功。
  -   取消开始后新到的同 Turn 工具请求必须拒绝。
  -   已完成且产生副作用的工具不能被改写成"未执行"。
  -   `effect_started`
      没有对应终态时，恢复上下文按"可能已发生副作用"处理。
  -   同一个 `operation_id` 的终止和摘要上报必须幂等。
  -   新 Turn 不复用旧 Turn 的 operation、审批状态或迟到结果。
  -   只终止本地监督器登记且 nonce 验证成功的监管域、进程组或 Job Object。
  -   服务端提供的 PID、PGID、cgroup 路径或 kill 参数一律不可信。
  -   文件摘要不包含正文；命令摘要必须脱敏并限制长度。
  -   `CANCELLED_UNKNOWN`
      必须显示项目、进程、端口和文件风险，并进入隔离态。
  -   强制解除隔离只能由本地交互用户完成两步确认。
  -   `/retry` 重新执行写入或命令时必须重新审批。

  ## 实施阶段

  ### 阶段一：Turn 生命周期和可复用连接

  -   引入 `turn_id` 和 Turn 状态机。
  -   删除连接级永久取消标志。
  -   把模型、Agent、工具和审批取消改成 Turn 作用域。
  -   取消后同一 WebSocket 可以接收下一条消息。
  -   拒绝旧 Turn 的迟到事件。

  完成标志：取消纯模型请求后，不重新连接即可继续对话。

  ### 阶段二：活动进程监督器

  -   实现 `ActiveProcessSupervisor`。
  -   WSL 命令创建并验证 operation 专属 systemd unit/cgroup、SID 和 PGID。
  -   实现有界 INT → TERM → KILL、cgroup 清理和空域确认。
  -   Windows 原生命令接入 Job Object。
  -   增加能力探测和 `PGID_ONLY` 降级提示。

  完成标志：真实键盘取消包含管道、多级子进程和二次 `setsid`
  的命令后，强监管域为空，延迟写入不发生。

  ### 阶段三：副作用摘要和上下文续接

  -   原生文件工具生成路径和哈希摘要。
  -   命令工具生成脱敏命令摘要、终止状态和部分文件观察结果。
  -   客户端写最小本地待发送记录。
  -   会话记录追加 `turn_cancelled` 和 `tool.effect`。
  -   实现 `InterruptionContextBuilder`、`/continue` 和 `/status`。

  完成标志：取消后模型能看到上一轮已完成工作、具体受影响路径、被中断工具和未知风险，不把中断工具当作未发生或已成功。

  ### 阶段四：原生启动器和异常退出

  -   使用原生 `clix.exe` 替代 `clix.cmd`。
  -   通过 `SetConsoleCtrlHandler` 把真实 Ctrl+C 投递给
      `CurrentTurnController`。
  -   增加崩溃后的活动记录和待发送摘要检查。
  -   验证终端关闭、JVM 崩溃和网络断开。

  完成标志：客户端记录 `RUNNING → CANCELLING → CANCELLED → IDLE`，连接保持
  `OPEN`，服务端完成 `turn.cancel → turn.cancelled`，同一 WebSocket
  能提交下一条消息。

  ## 测试矩阵

  ### 单元测试

  -   Turn 状态转换、重复取消和迟到事件隔离。
  -   单次模型取消不会污染下一 Turn。
  -   10 秒绝对截止时间不会被重试或第二次 Ctrl+C 延长。
  -   服务端 8 秒内终态化旧 Turn，并隔离供应商迟到输出。
  -   cgroup、Job Object、nonce、PID/PGID 和 PID 复用校验。
  -   `PGID_ONLY` 不沿 PPID 链追杀无法验证的进程。
  -   文件工具生成正确路径、操作类型和前后哈希。
  -   命令摘要脱敏、截断和外部副作用标记。
  -   `effect_started` 无终态时生成"可能已执行"上下文。
  -   中断上下文只注入一次，不包含原始工具指令。
  -   隔离态允许 `/status` 和固定只读核验，拒绝写入和任意命令。
  -   60 秒诊断截止和两步强制解除。

  ### 集成测试

  -   取消模型流，连接继续可用。
  -   取消 HITL，工具不启动，连接继续可用。
  -   `sleep 60 && write` 取消后最终文件不存在。
  -   shell、管道、多级子进程、`nohup`、`&` 和二次 `setsid`
      全部被强监管域清理。
  -   进程响应 SIGINT 时记录 `graceful`；忽略 INT/TERM 时最终被强制终止。
  -   10 秒仍无法确认时进入 `CANCELLED_UNKNOWN` 和隔离态。
  -   文件工具修改多个文件后取消，下一轮上下文精确展示路径和哈希。
  -   `execute_command` 修改文件后取消，上下文标记
      `effect_audit=partial`。
  -   命令发出网络请求时记录 `external_side_effects_possible=true`。
  -   取消后立即提交新 Turn，新旧结果不串线。
  -   取消后空闲数小时或跨天再继续，中断记录仍准确且不自动重放。
  -   客户端在摘要发送前崩溃，重启后能识别未发送记录但不重放工具。

  ### Windows 和 WSL 验收

  -   使用真实键盘 Ctrl+C，不只使用 `GenerateConsoleCtrlEvent` 模拟。
  -   PowerShell、Windows Terminal 和 cmd 分别验证。
  -   C 盘和其他盘符的 DrvFS 工作目录分别验证。
  -   WSL systemd/cgroup v2 能力不足时，严格模式拒绝，兼容模式明确显示
      `PGID_ONLY`。
  -   Windows 原生命令的 Job Object 被确认清空。
  -   原生 `clix.exe` 不出现"终止批处理操作吗"。
  -   Ctrl+C 后客户端依次进入 `CANCELLING → CANCELLED → IDLE`，连接始终
      `OPEN`，提示符在 10 秒内出现。
  -   客户端与服务端 trace 显示同一 `turn_id` 的完整取消时序，随后同一
      WebSocket 的新消息获得响应。
  -   模拟供应商忽略取消，旧 token 不进入新
      Turn，上下文正确显示远端未确认。

  自动化测试默认使用模拟模型，不调用真实供应商；真实模型只用于最终人工验收。

  ## 完成条件

  以下条件全部满足才可交付：

  1.  Ctrl+C 后当前 Turn 结束，但 WebSocket、登录态和会话仍可复用。
  2.  强监管模式下，前台命令的 cgroup 或 Job Object 被确认清空。
  3.  取消准确区分 graceful、forced、unknown 和 completed-before-cancel。
  4.  本地 transport 取消与供应商远端取消确认使用不同字段表达。
  5.  原生文件工具记录具体路径、操作类型和前后哈希。
  6.  Shell 命令只声明观察到的变化，并明确 partial、unavailable
      和外部副作用风险。
  7.  下一轮上下文包含原始目标、已完成步骤、中断位置、文件变化和未知风险。
  8.  中断工具不自动重放，重试仍执行安全检查和审批。
  9.  取消流程不自动回滚、不创建回滚专用前镜像，也不调用 `revert_turn`。
  10.  第一次 Ctrl+C 后不超过 10 秒恢复交互；状态未知时工作区进入隔离。
  11.  隔离态仍能对话和只读核验，新的有副作用操作不会与未知旧进程竞争。
  12.  真实 Windows 键盘、原生启动器、WSL cgroup 和 Windows Job Object
       验收通过。
  13.  登录、历史、auto/ask、文件工具和本地模式回归测试通过。

  ## 参考实现和平台文档

  -   [Linux cgroup v2 和
      cgroup.kill](https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html)
  -   [Windows Job
      Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)
  -   [Windows
      HandlerRoutine](https://learn.microsoft.com/en-us/windows/console/handlerroutine)
  -   [WSL systemd
      支持](https://learn.microsoft.com/en-us/windows/wsl/systemd)
  -   [systemd-run transient
      unit](https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html)
  -   [Codex CLI Ctrl+C 与结构化
      Interrupt](https://github.com/openai/codex/blob/main/codex-rs/tui/src/chatwidget/interaction.rs)
  -   [Gemini CLI
      进程组取消问题](https://github.com/google-gemini/gemini-cli/issues/30007)
  -   [OpenCode killTree
      实现](https://github.com/anomalyco/opencode/blob/dev/packages/core/src/shell.ts)

  ------------------------------------------------------------------------

  # V1.2 补充设计：七项边界的规范性实现约束

  本文节对前文存在歧义的地方具有优先解释权。实现与测试必须同时满足本节要求；若旧段落与本节冲突，以本节为准。

  ## A. 统一标识与事件模型

  每次执行必须使用全新且不可复用的： - `turn_id`：一次用户目标处理轮次。 -
  `tool_call_id`：模型在该 Turn 中提出的一次工具调用。 -
  `operation_id`：客户端实际执行的一次操作实例。工具调用被重新规划或再次执行时必须生成新
  ID。

  `tool_call_id` 不能充当 operation 的幂等身份。所有工具执行入口必须验证
  Turn 尚未终态化、operation 未被使用、工具请求属于当前
  Turn，且安全状态允许该工具类别执行。

  所有本地审计和服务端 Ledger 事件均追加写入，不原地覆盖。事件至少包含
  `event_id`、`event_type`、`turn_id`、`tool_call_id`（适用时）、`operation_id`（适用时）、`occurred_at`、`sequence`、`schema_version`
  和 `content_hash`。时间戳用于诊断，不用于替代 sequence 排序。

  ## B. effect_audit 判定表

  ---------------------------------------------------------------------------------------------------------------------

    工具类别              观察基线               默认等级                 可提升条件

  --------------------- ---------------------- ------------------------ -----------------------------------------------

    原生文件工具          单目标路径             verified（完整采集时）   路径规范化和前后状态采集全部成功
                          before/after 状态及                             
                          SHA-256                                         

    Git 项目              HEAD + porcelain       partial                  显式 output_paths
    execute_command       前后差集                                        且声明范围的基线/结果校验完整时，仅对声明范围
                                                                          verified

    非 Git                不采集                 unavailable              不允许推断式扫描
    execute_command，无                                                   
    output_paths                                                          

    非 Git                声明路径的前后元数据   partial                  不升级为全局 verified
    execute_command，有                                                   

    output_paths                                                          
    ---------------------------------------------------------------------------------------------------------------------

  `effect_audit=verified`
  不表示工具成功、不表示工作区一致、不表示可以回滚，也不表示不存在未声明的外部副作用。
  `audit_scope` 和 `audit_limitations` 是 effect schema
  的必填字段，必须把覆盖范围与等级分开表达： `audit_scope`
  描述本次实际观察范围；`audit_limitations` 描述未覆盖或无法确认的边界。
  没有已知限制时使用空数组，不得省略字段或用 `null` 代替。

  原生文件工具审计必须在同一 operation
  锁/事务作用域内完成：规范化路径、读取 before、执行、读取 after、追加
  JSONL、flush。若任何必要采集或落盘失败，禁止返回 verified。`outcome`
  独立记录 `completed | failed | cancelled_unknown`，不得从 audit
  等级反推。

  `effect_audit`
  的最终判定必须与工具终态化处于同一个原子提交边界：同一次终态化事务内确定
  `outcome`、`effect_audit`、观察摘要并追加终态事件。不得在工具终态化后启动异步任务再补算
  audit。若取消截止时间到达而观察尚未完成，必须在终态化时按已有证据写入
  `partial` 或
  `unavailable`；不得为了等待观察器而突破取消硬上限。该要求对原生文件工具和
  `execute_command` 均适用。

  ## C. /retry 的严格用户目标语义

  `/retry` 只允许执行以下动作： 1. 读取上一轮原始 `user_message`。 2.
  创建全新的 `turn_id`。 3. 把原始目标作为新 Turn 的用户消息提交。 4.
  将中断上下文交给模型，要求重新核验后规划。

  禁止： - 复制上一轮的 `tool.request` 或 `tool_call_id`； - 复用旧
  `operation_id`、审批 token、权限决定或工具参数； -
  在客户端缓存层把旧请求作为"重试请求"自动发送； - 因命令摘要相同就复用旧
  operation。

  `InterruptionContextBuilder` 必须在每次新 Turn
  注入的中断上下文末尾追加以下硬约束：

  > 上一轮有副作用工具被中断或可能已执行。不要假设它们未发生，也不要自动重放。先重新读取相关文件和
  > git diff，再决定是否需要重新执行。

  模型重新规划后提出的每一个工具调用都必须生成新 `tool_call_id` 和新
  `operation_id`，重新走完整 `ask/auto/deny`
  审批路径。即使新命令文本与旧命令完全相同，也必须作为新 operation 处理。

  ## D. 工作区安全门与审批策略分层

  `FORCED_RELEASED`
  的含义仅为：用户已完成两步确认，允许在监管域状态仍未完全确认的情况下解除隔离拦截。它不代表
  SAFE，也不构成工具批准。

  ------------------------------------------------------------------------

    控制层           职责                         FORCED_RELEASED 行为

  ---------------- ---------------------------- --------------------------

    Workspace Guard  阻止新操作与未知旧进程竞争   解除隔离门，但保留风险态

    Tool Permission  对每个工具计算               原策略不变
    Policy           allow/deny/ask               

    Force-release    确认用户知情放行             两步确认已完成

    Confirmation                                  
    ------------------------------------------------------------------------

  执行顺序固定为： 1. 校验当前 Turn/operation 身份和新鲜性； 2.
  检查工作区安全门； 3. 执行工具审批策略 `allow | deny | ask`； 4.
  审批完成后再次校验 Turn 和 operation； 5. 创建/登记 operation 并执行。

  `FORCED_RELEASED` 不得作为 `RemoteLocalTools` 或 `ToolRegistry` 的
  skip-approval 标志。`ask` 仍然 ask，`deny` 仍然 deny，`auto`
  仍按既有策略计算。状态提示持续显示风险标记，直到 `RecoveryInspector`
  确认监管域为空且核验通过才转为 SAFE。

  ## E. tool.effect 幂等、乱序和冲突

  effect 的逻辑幂等键为
  `turn_id + tool_call_id + operation_id`，但一个幂等键允许保留多个内容版本。服务端处理规则：

    收到的事件                    服务端行为

  ----------------------------- -------------------------------------------------

    同幂等键、同 content_hash     幂等重复，不新增 effect 版本
    同幂等键、不同 content_hash   追加 `effect_conflict`，保存双方版本
    sequence 较大                 当前恢复视图优先展示较大 sequence，但保留旧版本
    sequence 较小、晚到           仍追加为历史版本，不覆盖较新视图
    sequence 相同、hash 不同      追加 sequence_conflict 诊断，不静默选择

  服务端不得因 effect_conflict 关闭 WebSocket 或阻塞新
  Turn。冲突表示"观察记录不一致"，不表示某版本已被证明为真实当前磁盘状态。需要确认时重新读取当前文件或重新执行受限观察。`/status`
  和中断上下文必须显示冲突数量、版本数及"需重新核验"提示。

  ## F. 中断上下文注入与崩溃恢复

  `interruption_context_injected`
  的写入时机是：上下文已经附加到某个明确的新 Turn
  的模型请求，并且请求成功发送（以收到响应头或流开始作为可观测发送成功信号）之后。不得在仅构建上下文或仅排队请求时提前写入。

  事件建议字段： - `source_cancelled_turn_id` - `target_turn_id` -
  `context_digest` - `injection_attempt` - `request_id` - `injected_at`

  恢复规则： - 请求发送后、注入事件落盘前崩溃：事件不存在，下一次合适的新
  Turn 允许再次注入；重复内容提示模型忽略重复说明、以最新状态为准。 -
  注入事件落盘后、模型响应前崩溃：不得把上下文补发到已经开始或其他的新
  Turn；`/status` 仍从 Ledger 展示取消事实。 - 同一个
  `source_cancelled_turn_id` 的注入标记只能绑定一个
  `target_turn_id`。如果目标 Turn
  已创建但未能发送请求，不写注入事件。重试归属按以下规则确定： -
  若重试仍处于同一个 `target_turn_id` 的请求生命周期内，且该 Turn 尚未收到
  `turn.cancelled` 或 `turn.completed`，则继续使用原 `target_turn_id` 和原
  `request_id` 的幂等请求身份；不得另建并行请求或重复创建注入标记。 - 若该
  `target_turn_id`
  已经终态化，或用户已经提交了新的独立目标，则必须创建新的 `turn_id`；新
  Turn 重新根据 Ledger
  检查是否存在尚未注入的中断来源，并重新决定是否注入。 - 不得把旧
  `source_cancelled_turn_id` 的上下文自动串入任意并发
  Turn；注入标记始终绑定明确的 source/target Turn 对。

  中断说明是请求级 system/context 段，不是 assistant-authored
  历史消息，不得写入模型对话正文或 agentHistory。构建内容只使用可信结构化
  Ledger 字段，工具输出中的指令一律视为不可信数据。

  ## G. Provider Adapter 的迟到 chunk 隔离

  每个 Provider Adapter 实现 per-turn output gate，状态至少为
  `OPEN | QUARANTINED | CLOSED`。

  -   收到 `turn.cancel` 后，立即将对应 `turn_id` 的 gate 原子切换为
      `QUARANTINED`；该操作必须先于取消清理和业务层回调。
  -   gate 为 QUARANTINED 时，任何 chunk 不得触发
      `onToken`、`onMessageDelta`、工具调用解析、压缩/摘要、agentHistory
      或 Ledger 写入，也不得进入 UI 待发送队列。
  -   被隔离 chunk 只允许在 Adapter 内部增加
      `quarantined_chunk_count`，记录首尾 chunk
      的摘要哈希及首末时间；不得保存原文或 token 内容。
  -   `turn.cancelled` 汇总 `provider_output_quarantined=true`、chunk
      数、摘要哈希和观察时间窗。
  -   `transport_cancel_status=confirmed` 仅说明本地 transport
      已关闭，不等于 provider 远端确认；如果 gate 已启用，仍记录本 Turn
      曾被隔离。`provider_cancel_ack=confirmed` 且 Adapter 确认不会再有
      chunk 时，才可关闭 gate 并将最终状态记为 provider 已停止。
  -   `transport_cancel_status=unconfirmed` 或
      `provider_cancel_ack=unsupported/unknown` 时，gate
      必须保持隔离，直到流真正结束或请求对象被销毁。
  -   `CLOSED`
      的判定必须使用可观测的终止证据，不能由超时、取消请求已发送或本地停止读取推断：
      1.  收到供应商协议定义的明确流结束信号，例如 Anthropic 的
          `message_stop`、 OpenAI 兼容流的 `[DONE]`，或对应 Provider
          的等价终止事件；或
      2.  Adapter 已销毁该请求对象，且底层
          transport/连接已确认关闭，不可能再向该 Adapter 投递该请求的
          chunk。 仅收到 provider cancel
          acknowledgement、但流仍可能继续投递 chunk 时，不足以关闭 gate；
          gate 必须继续保持 `QUARANTINED`。每个 Adapter
          必须把其采用的终止证据类型记录为 `gate_close_reason`，例如
          `provider_stream_end` 或 `request_destroyed_transport_closed`。
  -   `RemoteAgentSession` 仍按 turn_id 做第二道过滤，但不能替代 Adapter
      第一时间拦截。

  若在 gate 启用前已经有 chunk 进入 Adapter 内部缓冲区，切换为 QUARANTINED
  时必须清空未分发缓冲区并计入丢弃计数；已经分发到业务层的 chunk
  不能假装从未发生，必须作为取消前已观察到的输出保留边界记录。

  ## H. 协议字段增补

  `tool.effect.payload` 建议统一为：

  ``` json
  {
    "turn_id": "turn-uuid",
    "tool_call_id": "tool-call-uuid",
    "operation_id": "operation-uuid",
    "sequence": 12,
    "schema_version": 2,
    "content_hash": "sha256:...",
    "tool": "execute_command",
    "outcome": "cancelled_unknown",
    "effect_audit": "partial",
    "audit_scope": "git_worktree_delta",
    "audit_limitations": ["external_side_effects_not_observable"],
    "observed_paths": [],
    "external_side_effects_possible": true
  }
  ```

  `turn.cancelled.payload` 增加诊断对象：

  ``` json
  {
    "turn_id": "turn-uuid",
    "connection_reusable": true,
    "workspace_state": "quarantined_unknown",
    "transport_cancel_status": "unconfirmed",
    "provider_cancel_ack": "unsupported",
    "provider_output_quarantined": true,
    "quarantined_chunk_count": 10,
    "quarantined_chunk_digest": "sha256:...",
    "quarantine_window": {
      "first_chunk_at": "...",
      "last_chunk_at": "..."
    }
  }
  ```

  字段缺失必须有 schema 默认值和版本兼容策略；不能把缺失字段推断成 `false`
  或 `confirmed`。

  ## I. 必须新增的验收测试

  1.  原生文件工具：成功、失败、取消三种 outcome 下，before/after
      均采集成功时 audit 都为 verified；哈希失败、路径越界、审计 JSONL
      flush 失败时不得为 verified。
  2.  `effect_audit` 与 `outcome` 独立：断言 `outcome=failed` 且
      `effect_audit=verified`
      是合法组合；展示层必须显示"操作失败/执行失败"，不得显示成功、
      完成或绿色成功对勾。`verified`
      只能呈现为"副作用记录完整/观察范围已记录"，不能映射为 工具成功状态。
  3.  原生文件工具：before → write → after → JSONL flush 顺序在同一
      operation 锁内；并发工具调用不能交叉污染哈希。
  4.  Git 命令：HEAD/status 前后差集只产生 observed_paths；无 output_paths
      时仍为 partial。
  5.  Git 命令：声明 output_paths 且该范围完整校验时只把声明范围标记
      verified，audit_scope 必须明确。
  6.  非 Git 命令：无 output_paths 时 unavailable、observed_paths
      为空、扫描器调用次数为零；中断上下文出现"未观察文件变化；请手工检查"。
  7.  非 Git 命令：声明 output_paths
      时只触达白名单路径；验证路径穿越、符号链接越界、目录大文件、文件消失/新建和
      mtime 精度。
  8.  `/retry`：新 turn_id、新 tool_call_id、新
      operation_id；旧审批不复用；相同命令摘要不构成复用理由。
  9.  `/retry`：客户端和服务端都拒绝旧 operation_id；拒绝后连接保持可用。
  10.  `FORCED_RELEASED`：auto 策略下原本允许的文件写入仍允许；ask
       策略下命令仍弹出确认；deny 仍拒绝。
  11.  `FORCED_RELEASED`：所有工具执行前检查
       turn_id、operation_id，不能重放旧请求。
  12.  effect 冲突：同幂等键同 hash 重发不产生新版本；同键异 hash 追加
       effect_conflict；sequence 乱序到达仍保留版本并按 sequence
       生成视图；新 Turn 不受阻塞。
  13.  中断注入：发送请求后、写 injected
       事件前崩溃，恢复后允许重新注入；事件写入后模型响应前崩溃，不重复注入。
  14.  中断注入：注入事件绑定 source turn 和 target
       turn，不允许串入其他并发 Turn；历史中不存在 assistant 中断占位符。
  15.  Provider Adapter：取消后继续推送 10 个 chunk，业务回调次数为
       0，计数为 10，Ledger/history/UI 队列均无这些 token。
  16.  Provider Adapter：取消前已分发 chunk 与取消后被隔离 chunk
       分开统计；transport confirmed 不被误写成 provider confirmed。
  17.  Provider Adapter：缓冲区已有未分发 chunk 时启用
       gate，缓冲区被清空且计入 quarantine 计数。
  18.  Provider Adapter：只有收到协议定义的流结束信号，或请求对象销毁且底层
       transport 确认关闭，才允许进入 CLOSED；仅有超时、cancel ack
       或停止读取不得关闭； 同时校验 `gate_close_reason`。
  19.  回归：正常完成、普通工具失败、HITL 拒绝、超时、网络断开和重复 Ctrl+C
       不破坏既有连接复用语义。

  ## J. 公开实现参考与适用边界

  以下公开实现只作为设计证据，不意味着其内部实现可直接复制：

  -   OpenAI Codex：响应流区分 drain 与
      cancel，并对取消请求设置截止时间；本设计借鉴其"请求/流生命周期与连接生命周期分离"，但仍额外要求
      per-turn Adapter chunk gate。
  -   Gemini CLI：公开 issue
      记录了中断占位符写入历史后被模型模仿的上下文污染风险；本设计采用请求级
      system/context 注入，不持久化 assistant 占位符。
  -   Claude Code：`PermissionDenied` hook 的 `retry: true`
      是允许模型再次尝试被拒绝的工具调用提示；本设计的 `/retry` 是新 Turn
      的用户目标重提交，二者不是同一语义。
  -   Cline：Auto Approve
      按工具权限配置执行；本设计把它与工作区隔离门拆开，避免 force release
      隐式变成全局 auto approve。
  -   OpenCode：公开 shell 实现提供进程树 kill 路径；相关 issue 也展示了
      kill/exit/tool-state 终态链路可能卡住。因此本设计把"发出
      kill""进程退出""监管域为空""工具终态落盘"作为四个独立事实。

  参考链接： - Codex response interrupt:
  https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/common.rs -
  Gemini CLI interruption context issue:
  https://github.com/google-gemini/gemini-cli/issues/29264 - Claude Code
  hooks reference: https://code.claude.com/docs/en/hooks - Cline
  auto-approve:
  https://github.com/cline/cline/blob/main/docs/features/auto-approve.mdx -
  OpenCode shell implementation:
  https://github.com/anomalyco/opencode/blob/dev/packages/core/src/shell.ts -
  OpenCode kill-chain issue:
  https://github.com/anomalyco/opencode/issues/38291

  ------------------------------------------------------------------------

  # V1.2 文档变更记录

  -   `audit_scope` 和 `audit_limitations` 改为 effect schema 必填字段。

  -   Provider Adapter 的 `CLOSED` 增加明确流结束或请求销毁且 transport
      关闭两类可验证条件，并记录 `gate_close_reason`。

  -   中断上下文请求重试明确区分同一 target Turn
      的请求内重试与终态化/新目标后的新 Turn。

  -   验收测试增加 `outcome=failed + effect_audit=verified` 的合法性及 UI
      不得显示成功状态的断言。

  -   将 `effect_audit`
      明确定义为结构化记录完整性，不再暗示操作成功或可回滚。

  -   原生文件工具采用同一 operation 锁内 before/after 哈希和 append-only
      JSONL flush。

  -   Git / 非 Git `execute_command` 观察策略分离，非 Git 默认不扫描。

  -   `/retry` 固定为新 Turn 的目标重提交，禁止工具级自动重放。

  -   `FORCED_RELEASED` 只解除工作区安全门，不改变 ask/auto/deny。

  -   `tool.effect` 冲突改为 append-only
      `effect_conflict`，保留所有版本，不阻塞新 Turn。

  -   中断上下文注入事件改为请求发送成功后落盘，并定义两种崩溃窗口。

  -   Provider Adapter 增加 per-turn chunk gate，取消后迟到 chunk
      不进入任何会话可见状态。

  -   新增协议字段、组件职责和 19 项专项验收测试。

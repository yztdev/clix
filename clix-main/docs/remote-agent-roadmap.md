# clix 远程开发路线与文档索引

状态：以下均为规划，尚未交付；版本号仅用于这条远程开发路线，与现有启动画面版本号无关。前一版达到验收标准后再进入下一版；测试不自动调用真实模型、不运行 Docker 或评测。

| 版本 | 用户得到的能力 | 开发文档 |
|---|---|---|
| V1 | Windows 本地运行 `clix`，服务器处理默认 Agent，本地读写项目和执行命令 | [V1 连接与本地工具](remote-agent-v1-development.md)；[首版交付方案](remote-agent-v1-delivery-plan.md)；[任务取消与会话续接](remote-agent-cancellation-session-continuity-development.md) |
| V2 | 远程 `/plan` 和 `/team`，在本地审阅计划 | [V2 规划与多智能体](remote-agent-v2-development.md) |
| V3 | 本地 MCP、Skill 和浏览器接入远程 Agent | [V3 扩展工具](remote-agent-v3-development.md) |
| V4 | 会话恢复、项目记忆、后台任务和快照回滚 | [V4 会话与任务](remote-agent-v4-development.md) |
| V5 | 图片、代码索引和跨平台安装 | [V5 多模态与发布](remote-agent-v5-development.md) |

各版共享原则：文件和本地命令在用户电脑执行；Agent 和模型调用在服务器执行；本地执行前仍需检查权限、路径和命令。连接断开后不能自动重放有副作用的操作。各版新增能力不能破坏原有本地模式或现有安全策略。微信远程接入、MCP OAuth 和 sampling 不纳入本路线，后续另行规划。

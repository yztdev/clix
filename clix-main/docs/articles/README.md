# clix 教程与面试题

这里是 clix 配套文章的唯一源。javabetter.cn 上 `/sidebar/itwanger/clix/` 下的页面是副本，改动先在这里完成，再用 `tools/sync-articles-to-javabetter.sh` 同步过去，线上 URL 保持不变。

文章 frontmatter 沿用 VuePress 格式（title / shortTitle / description / tag / category / author / date），图片统一走 CDN 链接，两边可以直接复用同一份 Markdown。改文章时以当前源码为准，涉及的源码事实优先引用 `AGENTS.md` 和对应类。

## 从零实现系列

| 文章 | 主题 |
|---|---|
| [build-agent-from-scratch.md](build-agent-from-scratch.md) | 第 1 期：ReAct 循环与工具注册 |
| [build-agent-p2-plan-execute.md](build-agent-p2-plan-execute.md) | 第 2 期：Plan-and-Execute 与 DAG 调度 |
| [build-agent-p3-memory.md](build-agent-p3-memory.md) | 第 3 期：短期记忆、长期记忆与上下文压缩 |
| [build-agent-p4-rag.md](build-agent-p4-rag.md) | 第 4 期：RAG 代码检索 |
| [clix-multi-agent.md](clix-multi-agent.md) | 第 5 期：Multi-Agent 协作 |
| [clix-hitl.md](clix-hitl.md) | 第 6 期：HITL 人工审批 |
| [clix-async-parallel.md](clix-async-parallel.md) | 第 7 期：异步与并行工具调用 |
| [clix-multi-model.md](clix-multi-model.md) | 第 8 期：多模型切换 |
| [clix-websearch-webfetch.md](clix-websearch-webfetch.md) | 第 9 期：联网能力 |
| [clix-mcp.md](clix-mcp.md) | 第 10 期：MCP 协议核心 |
| [clix-mcp-advanced.md](clix-mcp-advanced.md) | 第 11 期：MCP 高级能力 |
| [clix-chrome-devtools-mcp.md](clix-chrome-devtools-mcp.md) | 第 13 期：Chrome DevTools MCP |
| [clix-cdp-session-reuse.md](clix-cdp-session-reuse.md) | 第 14 期：CDP 会话复用 |
| [clix-skill-system.md](clix-skill-system.md) | 第 15 期：Skill 系统 |
| [clix-image-input.md](clix-image-input.md) | 第 21 期：图片输入 |
| [clix-agentbench.md](clix-agentbench.md) | 自建评测集：28 道题、证据检查与 LLM-as-a-Judge |

## 面试题

| 文章 | 主题 |
|---|---|
| [clix-interview-agent-core.md](clix-interview-agent-core.md) | Agent 核心架构 |
| [clix-interview-memory-context.md](clix-interview-memory-context.md) | 记忆与上下文 |
| [clix-interview-tool-security.md](clix-interview-tool-security.md) | 工具与安全 |
| [clix-interview-prompt-skill.md](clix-interview-prompt-skill.md) | Prompt 与 Skill |
| [clix-interview-multi-model.md](clix-interview-multi-model.md) | 多模型与成本 |
| [clix-interview-mcp.md](clix-interview-mcp.md) | MCP 协议与生态 |
| [clix-interview-productization.md](clix-interview-productization.md) | 产品化工程 |

## 其他

| 文章 | 主题 |
|---|---|
| [clix-xuexiluxian.md](clix-xuexiluxian.md) | 学习路线 |
| [clix-go-launch.md](clix-go-launch.md) | Go 版上线 |
| [clix-python-launch.md](clix-python-launch.md) | Python 版上线 |
| [clix-ts-launch.md](clix-ts-launch.md) | TypeScript 版上线 |

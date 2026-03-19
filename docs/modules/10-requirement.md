# requirement 模块

## 职责

`requirement` 负责把用户输入整理成 requirement 文档基线：

- 生成 draft
- 建 requirement doc
- 追加版本
- 确认版本

确认之后会把“需求已稳定”这个信号交给 `process`，进入 planning。

## 入站入口

- API:
  [RequirementAgentController](../../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementAgentController.java)
  - `generateDraft`
- API:
  [RequirementDocController](../../src/main/java/com/agentx/agentxbackend/requirement/api/RequirementDocController.java)
  - `createRequirementDoc`
  - `createVersion`
  - `confirm`

## 主要表

- `requirement_docs`
- `requirement_doc_versions`

## 关键代码入口

- draft:
  [RequirementAgentDraftService](../../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementAgentDraftService.java)
  - `generateDraft`
- doc command:
  [RequirementDocCommandService](../../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementDocCommandService.java)
  - `createRequirementDoc`
  - `createVersion`
  - `confirm`

## 在全链路里的位置

这一步是“把自然语言输入收敛成可交接基线”的地方。

真实闭环里，关键分水岭是：

1. draft 可以反复生成和修改
2. 只有 `confirm` 之后，`RequirementConfirmedEvent` 才会触发后续自动规划

## 想查什么就看哪里

- requirement draft 是怎么生成的
  - 看 [RequirementAgentDraftService](../../src/main/java/com/agentx/agentxbackend/requirement/application/RequirementAgentDraftService.java)
- requirement confirm 后为什么会拉起 ARCH_REVIEW
  - 看 [RequirementConfirmedEventListener](../../src/main/java/com/agentx/agentxbackend/process/infrastructure/external/RequirementConfirmedEventListener.java)
  - 再看 [RequirementConfirmedProcessManager](../../src/main/java/com/agentx/agentxbackend/process/application/RequirementConfirmedProcessManager.java)
- 某个 requirement 当前确认的是哪个版本
  - 查 `requirement_docs.confirmed_version`
  - 再对 `requirement_doc_versions`

## 调试入口

- API: `POST /api/v0/sessions/{sessionId}/requirement-agent/drafts`
- API: `POST /api/v0/requirement-docs/{docId}/confirm`
- SQL: `select * from requirement_docs where session_id = '<SESSION_ID>';`
- SQL: `select * from requirement_doc_versions where doc_id = '<DOC_ID>' order by version_no;`

## 工程优化思路

### 近期整理

- 把 draft 输入、输出和所用 LLM 配置关联得更清晰，减少“这版草案为什么长这样”的追溯成本。
- 为 `confirm` 增加更明确的幂等语义说明。

### 可维护性与可观测性

- requirement 版本变更补结构化 diff 摘要，减少只能靠全文比对。
- 让 requirement baseline 和 planning handoff 之间的映射更显式。

### 中长期演进

- 把 requirement 文档从“自由文本 + 简单状态”演进为“结构化段落 + 可追踪约束集合”。
- 给 requirement confirm 引入更严格的 schema 校验，减少下游 planning 的歧义处理负担。

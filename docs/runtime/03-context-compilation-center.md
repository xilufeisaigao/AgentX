# Context Compilation Center

本文只描述当前已经落地的上下文编译中心，不写未来实现细节。

## 1. 目标

`ContextCompilationCenter` 是四个 agent 的统一上下文入口，职责只有四个：

1. 取结构化事实
2. 取本地检索片段
3. 做裁剪、组装与落盘
4. 返回可追踪的 `CompiledContextPack`

它不负责做规划结论，也不替 agent 做推理。

## 2. 当前 Pack 类型

当前固定为四类 pack：

1. `REQUIREMENT`
2. `ARCHITECT`
3. `CODING`
4. `VERIFY`

语义固定：

1. `REQUIREMENT / ARCHITECT` 使用 workflow-scoped context
2. `CODING / VERIFY` 使用 task-scoped context

## 3. 请求与产物

输入类型：

- `ContextCompilationRequest`
- `ContextScope`
- `triggerType`

输出类型：

- `CompiledContextPack`
- `FactBundle`
- `RetrievalBundle`

当前 `CompiledContextPack` 固定包含：

1. `packType`
2. `scope`
3. `sourceFingerprint`
4. `artifactRef`
5. `contentJson`
6. `factBundle`
7. `retrievalBundle`
8. `compiledAt`

## 4. 事实来源

结构化事实仍然永远优先走精确取数，不进入模糊召回。

当前会进入 `FactBundle` 的主要对象包括：

1. `WorkflowRun / WorkflowRunEvent / WorkflowNodeRun`
2. `RequirementDoc / RequirementVersion`
3. `Ticket / TicketEvent`
4. `WorkModule / WorkTask / TaskDependency`
5. `TaskContextSnapshot / TaskRun / TaskRunEvent`
6. `GitWorkspace`

说明：

1. `TASK_BLOCKING` ticket 现在优先通过 `tickets.task_id` 进入 task-scoped pack。
2. `payload_json.taskId` 只保留兼容证据，不再作为上下文编译主查询路径。

## 5. Workflow 与 Task 两类范围

### workflow-scoped

用于：

1. requirement 多轮补洞
2. architect 规划与重规划

当前不新增 workflow context 主表，证据仍落在：

1. `workflow_node_runs.output_payload_json`
2. `workflow_node_run_events.data_json`
3. 本地 artifact 文件

### task-scoped

用于：

1. coding 多轮执行
2. verify 裁决

L5 真相继续承载在 `task_context_snapshots`：

1. `status`
2. `trigger_type`
3. `source_fingerprint`
4. `task_context_ref`
5. `expires_at`

当前 coding / verify pack 还会补入与当前 task 直接相关的运行守卫：

1. tool catalog
2. allowed command catalog
3. write scopes
4. open / answered task blocker 摘要
5. latest run / workspace evidence

## 6. Artifact 规则

编译产物落在本地 artifact root：

- `agentx.platform.context.artifact-root`

当前约束：

1. 每个 pack 都会写成一个 JSON 文件
2. 文件路径按 `packType / workflowRunId / taskId / runId` 组织
3. `sourceFingerprint` 用于判断 pack 是否可复用、是否过期
4. 超过 `max-pack-size` 时优先裁剪 retrieval 片段，不裁掉结构化事实

## 7. 当前结论

上下文编译中心现在已经成为 requirement / architect / coding / verify 的统一入口。

后续如果接：

1. coding 的 Unix 探索工具上下文
2. prompt 压缩
3. repo 级缓存
4. 外部知识源

都应该继续沿统一上下文入口扩展，而不是把上下文组装重新散落回各个 agent。

其中新的 coding 目标方案已经单独写入 `docs/runtime/07-unix-exploration-coding-context-design.md`。

# AgentX Platform Progress

## 使用规则

1. 每完成一个小步骤，必须更新本文件中的状态、产出和验收结果。
2. 每完成一个小步骤，必须提交到本地 git 仓库。
3. 提交信息要明确说明本次完成了什么，不写笼统信息。
4. 当前优先级：
   - 先 `domain`
   - 再固定主流程
   - 最后 `controlplane`

## 已完成阶段

### P10.1 Coding Context 设计改版（Docs）

- 目标
  - 明确把 coding 下一阶段目标从“继续加强代码 RAG”切换为“保留结构化事实层 + Unix 类工具主动探索代码 + 读宽写窄权限隔离”。
- 当前状态
  - `DONE`
- 备注
  - 已新增 `docs/runtime/07-unix-exploration-coding-context-design.md`
  - 已将 `docs/runtime/06-layered-vector-rag-design.md` 降级为历史归档
  - 已同步更新：
    - `docs/README.md`
    - `docs/runtime/03-context-compilation-center.md`
    - `docs/runtime/04-local-rag-and-code-indexing.md`
    - `docs/runtime/05-runtime-closure-assessment.md`
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `docs/deferred/01-runtime-v1-deferred.md`

### P10.2 Interview / Upgrade Design 收口（Docs）

- 目标
  - 把下一阶段能力升级蓝图和本轮项目面试口径收口到文档体系里，避免运行真相、设计稿和 interview bank 再次分叉。
- 当前状态
  - `DONE`
- 备注
  - 已新增：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
    - `docs/runtime/09-repo-graph-lite-design.md`
    - `docs/interview/agentx-end-to-end-project-interview.md`
  - 已更新：
    - `docs/architecture/02-fixed-coding-workflow.md`
    - `docs/architecture/04-state-machine-layers.md`
    - `docs/runtime/07-unix-exploration-coding-context-design.md`
    - `docs/interview/runtime-rag-and-agent-frameworks.md`
    - `docs/interview/README.md`
    - `docs/README.md`

### P10.3 审批处理中心设计补档（Docs）

- 目标
  - 把“审批处理中心”补成独立主线设计，并同步主线架构图、面试口径和进度索引，明确 `资源授权账本` 与 `外部集成契约` 都是其中的持久化事实。
- 当前状态
  - `DONE`
- 备注
  - 已新增：
    - `docs/runtime/10-approval-processing-center-design.md`
  - 已更新：
    - `docs/README.md`
    - `docs/architecture/02-fixed-coding-workflow.md`
    - `docs/architecture/04-state-machine-layers.md`
    - `docs/runtime/07-unix-exploration-coding-context-design.md`
    - `docs/runtime/08-agent-capability-upgrade-design.md`
    - `docs/interview/agentx-end-to-end-project-interview.md`
    - `docs/interview/runtime-rag-and-agent-frameworks.md`
    - `docs/interview/general-agent-system-design.md`
    - `progress.md`

### P0 仓库基线

- 目标
  - 初始化本地 git 仓库，固定当前项目起点。
- 当前状态
  - `DONE`
- 备注
  - 已完成提交：`chore: initialize local repository baseline`

### P1 文档与进度基线

- 目标
  - 固化实现顺序、阶段边界和每步验收方式。
- 当前状态
  - `DONE`
- 备注
  - 已创建 `progress.md`，并在 `AGENTS.md`、`README.md` 增加引用。

### P2 包结构重构

- 目标
  - 把项目从试验性骨架调整为稳定三层结构。
- 当前状态
  - `DONE`
- 备注
  - 已建立 `domain / runtime / controlplane` 三层稳定目录。

### P3 Domain 骨架

- 目标
  - 围绕固定主流程建立最小领域骨架。
- 当前状态
  - `DONE`
- 备注
  - 已补齐 `shared / catalog / flow / intake / planning / execution` 六个切片。
  - 验收通过：`.\mvnw.cmd -q -DskipTests compile`

### P4 MyBatis 持久化骨架

- 目标
  - 为 `domain` 端口提供 MyBatis 适配层。
- 当前状态
  - `DONE`
- 备注
  - 已切换为 `MyBatis + MySQL`。
  - 已补齐五个流程切片的 mapper / repository / type handler 骨架。
  - 验收通过：`.\mvnw.cmd -q test`

### P5 固定主流程应用骨架

- 目标
  - 打通 requirement -> ticket -> task -> task run 的固定主链。
- 当前状态
  - `DONE`
- 备注
  - 已完成 Runtime V1 第一版固定应用服务与 fake requirement / architect 闭环。
  - 已完成顶层 LangGraph 固定图和一次 clarification -> human answer -> resume 闭环。

## 本轮已完成的 Runtime 基础设施阶段

### P5.1 异步内核与调度基线

- 目标
  - 把 Runtime 从“图内同步跑完整个任务”改成“graph 只做顶层 reconciliation，底层由 dispatcher / supervisor 推进”。
- 产出
  - `WorkflowDriverService`
  - `WorkflowDriverScheduler`
  - `RuntimeSupervisorScheduler`
  - `FixedCodingWorkflowService.runUntilStable` 阻塞 facade 改造
  - `FixedCodingNodeExecutor` 节点职责改造
- 验收方式
  - graph 节点不再直接承载 fake coding / fake workspace 生命周期
  - workflow 能停驻在宏观状态而不是强依赖底层同步结束
- 当前状态
  - `DONE`
- 验收结果
  - 编译通过：`.\mvnw.cmd -q -DskipTests compile`
  - 单测通过：`.\mvnw.cmd -q test`
  - 文档已更新：
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `docs/runtime/02-runtime-v1-infrastructure.md`

### P5.2 执行契约与 Docker CLI Runtime

- 目标
  - 去掉对 agent 推理命令生成的依赖，改成 runtime 决定性的执行契约和真实 Docker CLI 运行时。
- 产出
  - `TaskExecutionContract`
  - `TaskExecutionContractBuilder`
  - `DeterministicTaskExecutionContractBuilder`
  - `AgentRuntime`
  - `DockerTaskRuntime`
  - `RuntimeInfrastructureProperties`
- 验收方式
  - task 能生成确定性的执行 / verify contract
  - Docker CLI 命令构造、观察和一次性执行可被测试
  - 运行证据进入 `execution_contract_json / runtime_metadata_json / task_run_events.data_json`
- 当前状态
  - `DONE`
- 验收结果
  - 单测通过：
    - `DeterministicTaskExecutionContractBuilderTests`
    - `DockerTaskRuntimeTests`
  - 配置已补齐到 `agentx.platform.runtime.*`

### P5.3 真实 Git Worktree / Merge / Verify 基线

- 目标
  - 用真实 Git worktree 替换 synthetic workspace，并把 merge-gate / verify 拉到真实基础设施层。
- 产出
  - `WorkspaceProvisioner`
  - `GitWorktreeWorkspaceService`
  - 真实 merge candidate / verify checkout / cleanup
- 验收方式
  - 每个 `TaskRun` 独立 worktree
  - merge-gate 产出真实 `merge_commit`
  - cleanup 回写真实 `cleanup_status`
- 当前状态
  - `DONE`
- 验收结果
  - 单测通过：`GitWorktreeWorkspaceServiceTests`
  - 顶层节点职责与实现文档已更新

### P5.4 中央派发器与 Supervisor 恢复

- 目标
  - 把任务消费改成真正的中心派发，并补齐 lease / heartbeat / retry / upgrade 的监督闭环。
- 产出
  - `TaskDispatcher`
  - `RuntimeSupervisorSweep`
  - MyBatis 查询扩展
  - dispatcher / supervisor 相关 schema 索引
- 验收方式
  - dispatcher 从全局 `READY` task claim
  - supervisor 能处理成功退出、失败重试、超过预算升级
  - 不再依赖 fake run / fake workspace 冒充真实基础设施
- 当前状态
  - `DONE`
- 验收结果
  - 单测通过：
    - `TaskDispatcherTests`
    - `RuntimeSupervisorSweepTests`
  - 已补 schema 索引：
    - `idx_task_runs_status_lease`
    - `idx_agent_pool_status_lease`
    - `idx_tickets_blocking_status_assignee`
    - `idx_work_tasks_status`

### P5.5 测试基线与文档收口

- 目标
  - 把测试基线切到 Testcontainers MySQL，并同步收口 runtime / database / deferred / progress 文档。
- 产出
  - `FixedCodingWorkflowRuntimeIT`
  - Testcontainers MySQL 集成测试基线
  - runtime / database / deferred / progress 文档更新
- 验收方式
  - `surefire` 跑单测
  - `failsafe` 跑集成 profile
  - 文档和当前代码实现一致
- 当前状态
  - `DONE`
- 验收结果
  - 单测通过：`.\mvnw.cmd -q test`
  - 集成验证通过：
    - `cmd /c "mvnw.cmd -q -Dit.test=FixedCodingWorkflowRuntimeIT verify"`
    - `cmd /c "mvnw.cmd -q verify"`
  - `2026-03-28` 已在当前机器上完成真实 Docker + Git + Testcontainers MySQL 验证，happy path 与 clarification/resume path 均通过
  - 已修复一处 Windows Docker CLI 参数拆分问题，避免任务脚本因为双引号和带空格字面量被截断，导致 marker 文件写入但未被 `git add/commit`
  - 文档已更新：
    - `docs/README.md`
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `docs/runtime/02-runtime-v1-infrastructure.md`
    - `docs/database/01-table-layer-map.md`
    - `docs/deferred/01-runtime-v1-deferred.md`
    - `progress.md`

### P6 Agent Kernel V1.1

- 目标
  - 先把 `requirement` 节点从本地 fake 切到真实 DeepSeek 驱动的多轮补洞与文档确认闭环。
- 产出
  - `AgentModelProperties`
  - `ModelGateway`
  - `DeepSeekOpenAiCompatibleGateway`
  - `RequirementConversationAgent`
  - `RequirementStageService`
  - `confirmRequirementDoc / editRequirementDoc`
  - requirement 阶段 graph 路由与 snapshot 语义调整
- 验收方式
  - 启动后允许“只有 workflow + ticket，没有 requirement doc”
  - requirement 可多轮补洞、成稿、修改、确认
  - 默认测试不出网，dev-only smoke 可直连 DeepSeek
- 当前状态
  - `DONE`
- 验收结果
  - 编译通过：`cmd /c "mvnw.cmd -q -DskipTests compile"`
  - 单测通过：`cmd /c "mvnw.cmd -q test"`
  - 默认整体验证通过：`cmd /c "mvnw.cmd -q verify"`
  - `2026-03-28` 已额外执行一次 DeepSeek requirement smoke：
    - 命令：`cmd /c "set AGENTX_LLM_SMOKE=true&& set AGENTX_DEEPSEEK_API_KEY=***&& mvnw.cmd -q -DfailIfNoTests=false -Dit.test=DeepSeekRequirementAgentSmokeIT verify"`
    - 结果：真实 DeepSeek 调用成功，`NEED_INPUT / DRAFT_READY` 结构化输出可被解析
  - 期间修复两处真实兼容问题：
    - DeepSeek 当前不接受 `response_format`，已改成 `schema-in-prompt + JSON parse`
    - `RequirementConversationContext` 的 prompt 序列化不能直接依赖 `Optional` 的 Jackson 默认处理
  - 文档已更新：
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `progress.md`

### P7-P10 全链路 Agent Kernel 基础版

- 目标
  - 把 requirement 之后的 `architect / coding / verify` 也切到统一 agent kernel，并把上下文编译中心与本地 RAG 基础设施独立出来。
- 产出
  - `ContextCompilationCenter`
  - `FactRetriever / ScopedFactResolver`
  - `RepoIndexService / WorkflowOverlayIndexService`
  - `LexicalChunkRetriever / SymbolRetriever / RetrievalQueryPlanner`
  - `ArchitectConversationAgent / CodingConversationAgent / VerifyDecisionAgent`
  - `PlanningGraphMaterializer`
  - `CodingSessionService / CodingToolExecutor / CodingSessionScheduler`
  - `docs/runtime/03-context-compilation-center.md`
  - `docs/runtime/04-local-rag-and-code-indexing.md`
- 验收方式
  - architect / coding / verify 都通过统一上下文入口取包
  - coding/verify 能检索当前 workflow 新写出来的代码
  - 单测保持全绿
  - `verify` 能完成默认构建；若当前机器无 Docker，则 docker/testcontainers 集成项允许被跳过，但不得报红
  - 增加一个真实业务感的学生管理系统场景，证明 requirement -> architect -> task DAG -> coding -> merge -> verify 可以在确定性输出驱动下完整跑通
  - 增加一个 dev-only 四 agent 真实 LLM smoke，验证 requirement / architect / coding / verify 能输出可解析结构
- 当前状态
  - `DONE`
- 验收结果
  - 编译通过：`cmd /c "mvnw.cmd -q -DskipTests compile"`
  - 单测通过：`cmd /c "mvnw.cmd -q test"`
  - 默认整体验证通过：`cmd /c "mvnw.cmd -q verify"`
  - `2026-03-29` 已在当前机器上完成真实 Docker + Git + Testcontainers MySQL 的默认验证：
    - 命令：`cmd /c "mvnw.cmd -q clean verify"`
    - 结果：通过
    - 学生管理系统场景 `shouldCompleteStudentManagementWorkflowWithDeterministicRuntime` 已通过，证明多任务 DAG、依赖约束、写域守卫、merge 和 verify 主链均可跑通
  - `2026-03-29` 已新增并执行开发态真实 LLM 全链路 smoke：
    - 命令：`cmd /c "mvnw.cmd -q -DfailIfNoTests=false -Dit.test=DeepSeekFullFlowSmokeIT -Dagentx.llm.smoke=true verify"`
    - 结果：通过
    - 说明：首次严格断言时，DeepSeek 在 architect 场景里输出了平台目录外的 `taskTemplateId=CREATE_OR_UPDATE_CLASS`；这说明结构化输出已可用，但平台语义对齐仍属于后续效果升级项，不再是基础设施缺口
  - 本轮已修复的基础设施断点：
    - `FixedCodingNodeExecutor` 已切到 architect/verify/context-center 新链路
    - `TaskDispatcher` 与 `TaskExecutionContract` 相关单测已对齐到持久容器 + context snapshot 基线
    - `FixedCodingWorkflowRuntimeIT` 已对齐新的 architect/coding/verify stub agent 基线，并补齐学生管理系统真实流程场景
    - `FactBundle` 已修复 null fact 不可序列化导致的上下文编译故障
    - `PlanningGraphMaterializer` 已修复 repeated tick 把进行中任务重置回 `READY` 的错误
    - verify/runtime failure evidence 已改成 nullable-safe payload 构造，避免失败证据回写时二次崩溃
  - 文档已更新：
    - `docs/README.md`
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `docs/runtime/03-context-compilation-center.md`
    - `docs/runtime/04-local-rag-and-code-indexing.md`
    - `docs/deferred/01-runtime-v1-deferred.md`
    - `progress.md`

### P10.1 Runtime 收口与设计整理阶段

- 目标
  - 把当前 runtime 做一次真正的收口：统一主链协议、工具执行、真实 smoke、最小 eval 基线、显式 task blocker 真相、死代码清理和文档同步。
- 产出
  - `ToolCall.callId`
  - `ToolCallNormalizer`
  - `tool-filesystem.list_directory`
  - `CodingSessionService` 同 run tool-call 幂等复用
  - `tickets.task_id` schema 补强与查询切换
  - `RuntimeReadinessService`
  - `AgentKernelEvalBaselineTests`
  - `docs/runtime/05-runtime-closure-assessment.md`
- 验收方式
  - `mvn verify` 默认全绿
  - DeepSeek requirement smoke 与 full-flow smoke 通过
  - coding 主链只保留统一 `ToolCall` 协议，不再保留旧动作枚举作为正式文档协议
  - 运行文档、database 文档、deferred 和 progress 与当前代码实现一致
- 当前状态
  - `DONE`
- 验收结果
  - `2026-03-29` 已重新执行单测：
    - 命令：`cmd /c "mvnw.cmd -q test"`
    - 结果：通过
  - `2026-03-29` 已重新执行默认整体验证：
    - 命令：`cmd /c "mvnw.cmd -q verify"`
    - 结果：通过
  - `2026-03-29` 已执行真实 DeepSeek smoke：
    - 命令：`cmd /c "mvnw.cmd -q -DfailIfNoTests=false -Dit.test=DeepSeekRequirementAgentSmokeIT,DeepSeekFullFlowSmokeIT -Dagentx.llm.smoke=true failsafe:integration-test failsafe:verify"`
    - 结果：通过
  - 本轮已收口的主链细节：
    - `ToolCall` 现在是 coding 正式唯一协议，载荷固定为 `callId / toolId / operation / arguments / summary`
    - `ToolCallNormalizer` 已负责兼容旧输入、补稳定 `callId`、把空 path 的 `read_file` 折叠成 `list_directory`
    - `ToolExecutor` 已统一 coding / verify / deterministic api-test 的执行证据形状
    - `CodingSessionService` 已实现相同 `callId` 的证据复用，不重复重放副作用
    - `tickets.task_id` 已成为 `TASK_BLOCKING` 的正式主查询路径，`payload_json.taskId` 仅保留兼容证据
    - `RuntimeReadinessService` 已把 Docker / repo / workspace / image mapping / DeepSeek smoke 配置检查收敛为只读 preflight
  - 本轮顺手修复的真实问题：
    - `CodingSessionService` 在已有 `agentInstance.currentWorkflowRunId()` 的情况下不再额外强依赖 workflow 归属查询，避免幂等场景下出现脆弱 fail fast
    - `recentTurnSummary(...)` 已按编码事件窗口裁剪，不再错误使用总事件数做 skip
  - 本轮已删除的误导性残留：
    - `LocalRequirementAgent`
    - `LocalArchitectAgent`
    - `LocalCodingAgent`
    - `LocalVerifyAgent`
    - `SyntheticWorkspaceService`
    - `CodingActionType`
    - `CodingTurnDecision`
  - 文档已更新：
    - `docs/README.md`
    - `docs/runtime/01-runtime-v1-implementation.md`
    - `docs/runtime/02-runtime-v1-infrastructure.md`
    - `docs/runtime/03-context-compilation-center.md`
    - `docs/runtime/04-local-rag-and-code-indexing.md`
    - `docs/runtime/05-runtime-closure-assessment.md`
    - `docs/database/01-table-layer-map.md`
    - `docs/deferred/01-runtime-v1-deferred.md`
    - `progress.md`

## 当前整体结论

1. Runtime 已完成从 fake 基础设施到真实 Docker / Git worktree / dispatcher / supervisor 的升级。
2. requirement 阶段现在已经具备真实 LLM 驱动的补洞、成稿、修订、确认闭环，`start(...)` 不再伪造已确认需求文档。
3. 顶层 graph 现在只负责 reconciliation，L4/L5 才承载 task / run / workspace / lease / heartbeat 真相。
4. 当前主链已经具备基础版四 agent kernel、上下文编译中心和本地 lexical/symbol RAG。
5. 当前 runtime 已具备统一 `ToolCall` 协议、显式 `TASK_BLOCKING.task_id`、readiness preflight、离线 eval baseline 和真实 DeepSeek smoke，不再是“基础设施仍不可信”的阶段。
6. command-side controlplane baseline 已经落地，workflow / ticket / requirement / agent definition 的人工入口不再需要通过测试桩或直接调 use case。
7. 下一批可以先快速推进 query-side controlplane / UI，再把主要精力转到 agent 效果升级与更完整评测。
8. Eval Center V1 已经把 workflow 评测从“零散 smoke 证据”收口成统一 artifact 三件套，下一步效果优化可以围绕 scenario pack、报告解读和 regression 展开。
9. `2026-03-30` 起，评测输出默认落到项目内 `artifacts/*` 专用目录，不再默认放 `target/*`。

## 下一阶段

### P11.1 Controlplane Command API 基线

- 目标
  - 在不引入前端和 query-side read model 的前提下，先把用户可操作的 command-side controlplane 入口立起来。
- 产出
  - `POST /api/v1/controlplane/workflows`
  - `POST /api/v1/controlplane/workflows/{workflowRunId}/drive`
  - `POST /api/v1/controlplane/tickets/{ticketId}/answer`
  - `PUT /api/v1/controlplane/workflows/{workflowRunId}/requirement/current`
  - `POST /api/v1/controlplane/workflows/{workflowRunId}/requirement/confirm`
  - `POST /api/v1/controlplane/agents`
  - `PATCH /api/v1/controlplane/agents/{agentId}/enable`
  - `PATCH /api/v1/controlplane/agents/{agentId}/disable`
  - `PUT /api/v1/controlplane/agents/{agentId}/capability-packs`
  - command-side 文档与 controller 级测试
- 验收方式
  - 不绕过既有 use case / 聚合命令直接改表
  - controller 层具备请求校验、错误码映射和基本路由测试
  - `mvn test` 与 `mvn verify` 继续全绿
- 当前状态
  - `DONE`
- 验收结果
  - 代码已补齐：
    - `WorkflowCommandFacade`
    - `AgentDefinitionCommandService`
    - `WorkflowCommandController`
    - `TicketCommandController`
    - `AgentCommandController`
    - `ControlplaneExceptionHandler`
  - controller/service 测试已补齐：
    - `WorkflowCommandFacadeTests`
    - `AgentDefinitionCommandServiceTests`
    - `WorkflowCommandControllerTests`
    - `TicketCommandControllerTests`
    - `AgentCommandControllerTests`
 - 文档已更新：
    - `docs/controlplane/01-controlplane-v1-command-api.md`
    - `docs/deferred/01-runtime-v1-deferred.md`
    - `docs/README.md`
    - `progress.md`
  - `2026-03-29` 已执行命令侧专项测试：
    - 命令：`cmd /c "mvnw.cmd -q -Dtest=WorkflowCommandFacadeTests,AgentDefinitionCommandServiceTests,WorkflowCommandControllerTests,TicketCommandControllerTests,AgentCommandControllerTests test"`
    - 结果：通过
  - `2026-03-29` 已重新执行默认整体验证：
    - 命令：`cmd /c "mvnw.cmd -q verify"`
    - 结果：通过
  - `2026-03-29` 已额外执行一次真实 DeepSeek 全链路学生管理 smoke：
    - 命令：`$env:AGENTX_LLM_SMOKE='true'; $env:AGENTX_DEEPSEEK_API_KEY='***'; ./mvnw.cmd -q -Dtest=DeepSeekRealWorkflowRuntimeIT test`
    - 结果：通过
    - requirement 与 verify 的真实输出被接受；architect 与 coding 的真实输出已留存证据，但因未完全对齐平台模板/工具协议，使用 manual fallback 沿同一正式协议把 workflow 推到完成
    - 导出代码位置与优化记录已落：
      - `docs/agentkernel/01-real-llm-full-flow-smoke-findings.md`

### P11.2 Controlplane Query / UI 基线

- 目标
  - 在 command-side 基线已经稳定的前提下，再补 query-side controlplane API 和后续展示面所需的读模型入口。
- 产出
  - workflow / ticket / task / run / workspace 查询接口
  - inbox / runtime ops / DAG / task-run 展示所需读接口
  - 为后续 UI 提供稳定只读数据入口
- 验收方式
  - 不破坏当前固定主链和真实 runtime / context / retrieval 基础设施
  - command/query 边界清楚，不把展示 DTO 反向污染主链
- 当前状态
  - `PENDING`

### P11.3 Eval Center V1

- 目标
  - 把现有 smoke artifact、context compilation evidence、task run events 和 workflow snapshot 收口为统一评测中心，输出稳定的 workflow 评测报告，并定义配套报告解读 skill。
- 产出
  - `runtime.evaluation`
    - `WorkflowEvalCenter`
    - `WorkflowEvalTraceCollector`
    - `EvalScenario`
    - `EvalEvidenceBundle`
    - `EvalScorecard`
  - strict real workflow eval pack
    - `RealWorkflowEvalScenarioPack`
    - `RealWorkflowEvalRunner`
    - `DeepSeekStrictWorkflowEvalIT`
    - `src/test/resources/evaluation/scenarios/student-management-real-strict.json`
  - workflow eval 三件套
    - `raw-evidence.json`
    - `scorecard.json`
    - `workflow-eval-report.md`
  - `WorkflowEvalCenterTests`
  - `docs/evaluation/*`
  - `.codex/skills/agentx-eval-report-reader/*`
  - `.codex/skills/agentx-eval-scenario-pack-author/*`
- 验收方式
  - baseline agent eval 能稳定生成 `scorecard.json`
  - 真实 workflow smoke 能稳定生成三件套
  - 严格真实 workflow eval 能从 scenario pack 读取场景，并在真实 LLM 卡住时停止推进但仍输出报告
  - 报告能指出 catalog/template 违规、绝对路径工具协议违规、RAG 缺失等典型问题
  - skill 文档化后可按固定结构解读报告并给出优化计划
- 当前状态
  - `DONE`
- 验收结果
  - 已完成接入：
    - `DefaultContextCompilationCenter` 会采集 `CompiledContextPack` 证据到 `WorkflowEvalTraceCollector`
    - `DeepSeekRealWorkflowRuntimeIT` 会在 smoke artifact 基础上生成完整 workflow eval 报告
    - `AgentKernelEvalBaselineTests` 会使用同一 artifact writer 输出 baseline eval 结果
  - 已完成 artifact 稳定化：
    - `WorkflowEvalCenter` 现在使用内部注册好的 Jackson modules 写 artifact，避免 `LocalDateTime / Optional` 在测试或离线 runner 中序列化失败
  - 已新增严格真实 runner：
    - `DeepSeekStrictWorkflowEvalIT` 不再通过手工构造结构化输出推进主链
    - `RealWorkflowEvalRunner` 会在 timeout / unexpected human wait 时终止 active runs 并照样生成报告
    - scenario pack 改为走 `src/test/resources/evaluation/scenarios/*.json`
    - 评测目录已迁移到项目内 `artifacts/evaluation-runs/*` 与 `artifacts/evaluation-reports/*`
    - 真实 workflow run 根目录会额外生成 `README.md`，集中索引 `workflow-result.json / raw-evidence.json / scorecard.json / workflow-eval-report.md`
    - 已新增专项测试：
      - `WorkflowEvalCenterTests`
  - `2026-03-30` 已执行针对性验证：
    - 命令：`cmd /c "mvnw.cmd -q -Dtest=AgentKernelEvalBaselineTests,WorkflowEvalCenterTests test"`
    - 结果：通过
    - 命令：`cmd /c "mvnw.cmd -q -DskipTests test-compile"`
    - 结果：通过
  - 文档已更新：
    - `docs/evaluation/01-eval-center-overview.md`
    - `docs/evaluation/02-dimension-catalog.md`
    - `docs/evaluation/03-workflow-report-schema.md`
    - `docs/evaluation/04-scenario-pack-and-regression.md`
    - `docs/evaluation/05-agentx-eval-report-reader-skill.md`
    - `docs/evaluation/06-real-workflow-scenario-pack.md`
    - `docs/README.md`
    - `docs/deferred/01-runtime-v1-deferred.md`
    - `progress.md`

### P12 效果升级与治理

- 目标
  - 在控制面和展示面可用之后，再系统推进 Unix exploration、审批治理、spec/verify、repo graph 和 eval-driven upgrade。
- 产出
  - coding unix exploration 主链切换
  - requirement completeness gate
  - approval processing center
  - external integration contract
  - spec-first / verify-first
  - repo graph lite
  - write scope overlap governance
  - historical decision reuse
  - eval-driven upgrade loop
- 验收方式
  - 不破坏当前固定主链和真实 runtime / context / retrieval 基础设施
- 当前状态
  - `PENDING`
- 备注
  - `2026-04-02` 已先完成分层向量 RAG 设计稿：
    - `docs/runtime/06-layered-vector-rag-design.md`
  - 设计约束：
    - 保留 `FactRetriever -> RetrievalBundle -> ContextCompilationCenter` 主边界
    - 保留 `base repo index + workflow overlay index` 的分层索引思路
    - 文档已明确标注“设计完成、代码待实现”，当前运行真相仍然是 lexical / symbol baseline；该文档现已降级为归档对照，不再是 coding 主路径主线
  - `2026-04-02` 已补 interview bank 与归档 skill：
    - `docs/interview/README.md`
    - `docs/interview/runtime-rag-and-agent-frameworks.md`
    - `.codex/skills/agentx-interview-bank-curator/SKILL.md`
  - `2026-04-04` 已补充 AgentX 端到端项目面试题口径：
    - `docs/interview/agentx-end-to-end-project-interview.md`
    - 重点补齐 SDD / TDD、模块级验证、前后端联调测试思路、老项目增量分析、repo graph lite 与图数据库边界、探索成本控制、verify agent 角色与 merge/verify 时序等问题
  - `2026-04-07` 已继续补充 interview bank：
    - `docs/interview/general-agent-system-design.md`
    - 新增“Agent 学术组成、幻觉治理、工业化落地与 AgentX 映射”答法，统一了通用理论、工业工程化做法和项目内固定主链口径
    - 新增“多 Agent / 异步任务防止上下文污染”“长短期记忆与结构化真相边界”“Agent 设计范式与 AgentX 选型”三组答法，统一了 context pack、状态机、中心派发与局部 ReAct-like 回路的面试表达
    - 同日继续补充“记忆压缩方法”“长对话 / 上下文窗口不足处理策略”“AgentX 中 ReAct-like 回路详细实现”三组答法，统一了短期工作记忆、长期可复用知识、system of record 与受控工具回路的面试表达
    - 同日继续补充“探索型 AI / Agent 岗位匹配度”“Agent 编排 / tool calling / RAG 的工程落地证明”“代码检索为何更偏 grep 而非向量数据库”“解题准确率提升的评测归因闭环与工具触发边界”四组答法，统一了项目经历、代码探索路线和 eval-driven optimization 的面试表达
    - 同日新增 `docs/interview/evaluation-and-rag-quality.md`，集中归档“RAG 质量指标”“golden set 构建”“retrieval scenario pack”“检索失败归因”“regression 比较”“badcase review”“为何当前不优先 embedding”“局部检索变强但整体 workflow 不涨分”八组更底层的评测口径
    - 同日继续补充“架构师 agent 的职责边界”“为什么 approval processing center 与 repo graph lite 都挂在 architect 旁边”两组答法，统一了规划层、资源边界和代码边界在主链中的位置表达
  - 归档规则：
    - 新问题优先插入现有主题文档
    - 若无合适主题，再在 `docs/interview/` 下新建文档
    - 每题不编号，只标 `重要程度`
  - 当前主线设计索引：
    - `docs/runtime/07-unix-exploration-coding-context-design.md`
    - `docs/runtime/08-agent-capability-upgrade-design.md`
    - `docs/runtime/09-repo-graph-lite-design.md`
    - `docs/runtime/10-approval-processing-center-design.md`

### P12.1 Coding Unix Exploration 主链切换

- 目标
  - 把 coding 阶段从“平台预注入 repo retrieval 片段”切到“结构化事实层 + Unix 类只读探索工具主动确认代码真相”。
- 优先级
  - `P0`
- 当前状态
  - `DONE`
- 产出
  - `TaskExecutionContract` 已增加：
    - `runtimePlatform`
    - `shellFamily`
    - `workspaceRoot`
    - `repoRoot`
    - `explorationRoots`
    - `workspaceReadPolicy`
    - `explorationCommandCatalog`
  - `CapabilityRuntimeAssembly / StackProfileManifest` 已支持独立的 `explorationCommands` 目录编译，不再和执行类 `allowedCommands` 混用
  - `DeterministicTaskExecutionContractBuilder` 已固定输出 Linux-first / POSIX shell / broad read facts，并把 `explorationRoots` 写入 coding contract
  - `DefaultScopedFactResolver` 已把新环境事实、`explorationCommandCatalog` 和扩展后的 `runtimeGuardrails` 暴露给 coding prompt
  - `DefaultContextCompilationCenter` 已对 `ContextPackType.CODING` 一次性切掉 repo retrieval snippets；architect / verify retrieval 保持原样
  - `ToolRegistry / ToolExecutor / ToolCallNormalizer` 已切换到新工具协议：
    - 新 filesystem operation：
      - `glob_files`
      - `read_range`
      - `head_file`
      - `tail_file`
      - `grep_text`
    - `search_text` 只保留兼容 alias，进入执行链前会规范化为 `grep_text`
    - `tool-shell` 已拆成：
      - `run_command`
      - `run_exploration_command`
    - exploration command 已改为平台侧 `argvTemplate + allowedArgs` 展开，模型不再生成 raw shell string
  - `CodingConversationAgent` prompt 已切到“先看结构化边界 -> 再做 Unix exploration -> 最后写”的主链，并显式展示：
    - shell/runtime 环境事实
    - workspace / repo root
    - exploration roots
    - workspace read policy
    - write scopes
    - exploration / execution commandIds
  - stack profile 已补齐 exploration command catalog：
    - `src/main/resources/stack-profiles/java-backend-maven.json`
    - `src/main/resources/stack-profiles/ts-fullstack-pnpm-monorepo.json`
- 验收结果
  - 已新增/更新专项测试：
    - `DeterministicTaskExecutionContractBuilderTests`
    - `CapabilityRuntimeAssemblerTests`
    - `ToolCallNormalizerTests`
    - `ToolExecutorTests`
    - `DefaultContextCompilationCenterTests`
    - `CodingSessionServiceTests`
    - `TaskDispatcherTests`
    - `AgentKernelEvalBaselineTests`
  - `2026-04-04` 已执行针对性验证：
    - 命令：`cmd /c "mvnw.cmd -q -Dtest=DeterministicTaskExecutionContractBuilderTests,CapabilityRuntimeAssemblerTests,ToolCallNormalizerTests,ToolExecutorTests,CodingSessionServiceTests,TaskDispatcherTests,AgentKernelEvalBaselineTests,DefaultContextCompilationCenterTests test"`
    - 结果：通过
  - `2026-04-04` 已执行全量单测回归：
    - 命令：`cmd /c "mvnw.cmd -q test"`
    - 结果：通过
  - `2026-04-04` 已完成一轮 P12.1 残留清理：
    - 对外可见 tool catalog 与新测试口径已统一只保留 `grep_text`
    - `search_text` 只保留在 `ToolCallNormalizer` 内部作为兼容 alias，不再作为主链公开 operation
- 备注
  - 这是当前 coding 方向与 interview 口径统一的第一优先级事项，现已完成首版代码切换。
  - 本轮明确未做：
    - PowerShell exploration 模板
    - architect / verify retrieval 策略切换
    - repo graph lite 运行时裁剪
    - approval processing center
- 设计位置
  - 主设计：
    - `docs/runtime/07-unix-exploration-coding-context-design.md`
  - 能力升级蓝图汇总：
    - `docs/runtime/08-agent-capability-upgrade-design.md`

### P12.2 Requirement Completeness Gate

- 目标
  - 把 requirement 阶段从“模板补洞”升级为“完整度门禁”，减少 architect 阶段才暴露关键缺口。
- 优先级
  - `P1`
- 计划产出
  - completeness checklist：目标、范围、角色、主流程、异常流程、验收标准、非功能要求、外部依赖、权限与数据约束
  - requirement 阶段 gate 规则：未满足门禁不放行 architect
  - 缺口项进入正式 ticket / blocker，而不是留在自由对话里
- 备注
  - 对 production-grade 需求确认与 interview 表达都有直接帮助。
- 设计位置
  - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 相关主链图：
    - `docs/architecture/02-fixed-coding-workflow.md`
  - 相关状态机口径：
    - `docs/architecture/04-state-machine-layers.md`

### P12.3 Approval Processing Center（含 Resource Grant Ledger）

- 目标
  - 把“一次次重复审批”升级为审批处理中心，统一承接规范化资源请求、异步审批、grant 复用和结果分发。
- 优先级
  - `P1`
- 计划产出
  - 资源请求定义与入口：支持 architect 发起规范化资源请求
  - 异步审批队列 / 审批流适配：支持请求异步流转
  - 资源授权账本：沉淀可复用 grant、scope、批准人、有效期与环境绑定
  - 唤起与批量分发：支持“审批回来立刻唤起 architect”或“下次统一补入”
  - context compilation 回填最近有效 grant / pending request 摘要，避免同类问题反复打断用户
- 备注
  - 该能力应沿既有 ticket / context 主链扩展，不另造平行审批系统，也不替代 architect。
  - `2026-04-04` 已补队列选型设计：
    - 审批处理中心第一版消息队列口径改为 `RocketMQ`
    - 审批请求、grant、integration contract 的真相仍然落 MySQL
    - RocketMQ 负责审批请求投递、审批结果回流和 architect 唤起等异步消息流转
- 设计位置
  - 总入口：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 详细设计：
    - `docs/runtime/10-approval-processing-center-design.md`
  - 相关主链图：
    - `docs/architecture/02-fixed-coding-workflow.md`
  - 相关状态机口径：
    - `docs/architecture/04-state-machine-layers.md`

### P12.4 External Integration Contract

- 目标
  - 把第三方系统接入从“自由描述或临时 skill”升级为结构化 integration contract。
- 优先级
  - `P1`
- 计划产出
  - endpoint、method、auth、request/response schema、environment、owner、grant 绑定关系
  - `TaskExecutionContract` / tool catalog 暴露 allowlisted endpoint，而不是让 agent 自由拼 HTTP 请求
  - requirement / architect 阶段对外部依赖信息缺口走标准澄清票据
- 备注
  - 该项与 Approval Processing Center 协同设计，是其中的契约事实层。
- 设计位置
  - 总入口：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 详细设计：
    - `docs/runtime/10-approval-processing-center-design.md`
  - 相关主链图：
    - `docs/architecture/02-fixed-coding-workflow.md`
  - 当前已形成审批处理中心中的 contract 设计稿；后续落代码前可继续补 contract schema / auth model 细化稿。

### P12.5 Spec-First / Verify-First 机制

- 目标
  - 把 SDD / TDD 的有效部分内化成 AgentX 主链机制，而不是停留在面试概念层。
- 优先级
  - `P2`
- 计划产出
  - architect 先产出 task spec、write scopes、verify expectations，再创建 coding task
  - 对关键任务增加 verify-first 子任务，例如测试脚本、验收脚本、API smoke 先行
  - coding 交付与 verify 裁决都回指同一份 spec / acceptance truth
  - merge-gate 只负责把 task 交付并入模块集成候选，不再在每个 task merge 后立即启动 verify agent
  - 当 `WorkModule` 达到可集成状态时，再由模块集成测试闸门执行确定性集成测试，并在证据产出后启动 verify agent
- 备注
  - 不单独引入重框架，优先复用现有 task template / verify contract 机制。
  - `2026-04-04` 已完成设计调整与文档同步：
    - verify agent 的职责边界后移到模块级集成测试之后
    - architecture / runtime design / interview 口径已统一改为 `merge-gate -> integration-test-gate -> verify`
  - 当前代码缺口：
    - 现有 Runtime V1 代码仍然是 task `DELIVERED` 后直接进入 verify
    - 尚未补模块集成候选聚合、模块集成 checkout、模块级 deterministic integration contract、module-scoped verify context
    - 尚未把 `VERIFYING` 的触发条件从“单 task merge 成功”切到“模块达到可集成状态”
- 设计位置
  - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 相关主链图：
    - `docs/architecture/02-fixed-coding-workflow.md`
  - 相关状态机口径：
    - `docs/architecture/04-state-machine-layers.md`

### P12.6 Repo Graph Lite

- 目标
  - 为大仓库增量开发补一个轻量代码图视角，用于帮助 architect 理解模块结构、帮助 coding 缩小探索范围。
- 优先级
  - `P2`
- 计划产出
  - 基于 path / symbol / import / module relation 的轻量 repo graph
  - 为 architect 提供 exploration roots 推荐，为 coding 提供搜索范围提示
  - 保持“图是辅助视图，不替代真实 Unix 探索主路径”
- 备注
  - 第一版不急于引入图数据库，先验证图视角是否真正提升规划与探索效率。
- 设计位置
  - 总入口：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 详细设计：
    - `docs/runtime/09-repo-graph-lite-design.md`
  - interview 口径沉淀：
    - `docs/interview/runtime-rag-and-agent-frameworks.md`
    - `docs/interview/agentx-end-to-end-project-interview.md`

### P12.7 Write Scope Overlap Governance

- 目标
  - 把并发冲突尽量前移到 architect / dispatcher，而不是等到 merge 阶段才发现。
- 优先级
  - `P2`
- 计划产出
  - task 下发前检查 write scope 是否重叠
  - 对潜在冲突任务改为串行、拆分或 blocker 重规划
  - merge-gate 冲突继续作为最终兜底，而不是唯一治理手段
- 备注
  - 该项比“增量代码向量冲突检测”更符合当前固定主链。
- 设计位置
  - coding 主线与并发处理：
    - `docs/runtime/07-unix-exploration-coding-context-design.md`
  - 能力升级蓝图汇总：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
  - 相关主链图：
    - `docs/architecture/02-fixed-coding-workflow.md`
  - 相关状态机口径：
    - `docs/architecture/04-state-machine-layers.md`

### P12.8 Historical Decision Reuse

- 目标
  - 优先复用历史 task spec、blocker 答案、verify 脚本和失败原因摘要，而不是继续扩大代码 RAG 比例。
- 优先级
  - `P3`
- 计划产出
  - 结构化历史经验摘要
  - architect / coding / verify pack 的任务级相似案例提示
  - 历史 blocker / approval / verify evidence 的范围化复用
- 备注
  - 重点复用“决策和边界”，不是直接复用大段代码片段。
- 设计位置
  - 当前仅在 `progress.md` 中保留升级方向，尚未形成独立设计稿。
  - 后续建议优先挂靠：
    - `docs/runtime/08-agent-capability-upgrade-design.md`
    - `docs/runtime/03-context-compilation-center.md`
    - `docs/interview/agentx-end-to-end-project-interview.md`

### P12.9 Eval-Driven Upgrade Loop

- 目标
  - 把后续所有效果升级都接入 Eval Center，而不是靠主观感受判断优化是否成立。
- 优先级
  - `P3`
- 计划产出
  - 为 Unix exploration、requirement gate、approval processing center、repo graph 等能力补 scenario pack
  - 在 `DAG_QUALITY / TOOL_PROTOCOL / HUMAN_IN_LOOP / EFFICIENCY` 维度增加回归指标
  - 报告中显式区分“方向正确但未落地”与“已落地但效果不佳”
- 备注
  - 该项是后续迭代质量控制的总抓手。
- 设计位置
  - 当前仅在 `progress.md` 中保留升级方向，评测底座真相见：
    - `docs/evaluation/01-eval-center-overview.md`
    - `docs/evaluation/02-dimension-catalog.md`
    - `docs/evaluation/04-scenario-pack-and-regression.md`
  - 后续建议补一份专门的 upgrade-to-eval mapping 设计稿。

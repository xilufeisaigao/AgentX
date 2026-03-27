# AgentX Platform Development Guide

本项目是新的 greenfield Agent 平台内核，不延续旧控制面的胶水式扩展。

目标很明确：

1. 固定工作流结构。
2. 动态注册和控制 Agent。
3. 通过能力包而不是散装 skill/tool 来调度任务。
4. 先把核心数据真相和主流程打稳，再扩能力。

## 1. 代码三层

### `domain`

- 定义平台核心模型、约束和状态转换。
- 关注对象：
  - Agent
  - Capability Pack
  - Workflow Template / Workflow Run
  - Requirement / Ticket
  - Task / Task Run / Workspace
- 不放 Web、Spring MVC、SQL Mapper、Docker 细节。

### `controlplane`

- 面向用户和运维侧的控制面。
- 负责：
  - Agent 注册、目录查询、能力查看
  - Workflow Run 启动和节点绑定
  - Requirement 文档、Ticket、任务图
  - 固定工作流编排和 HITL 决策面
- 可以依赖 `domain`。

### `runtime`

- 负责执行时集成与运行时工件。
- 负责：
  - LangGraph4j / LangChain4j 适配
  - Docker Agent Runtime
  - Agent Pool
  - Context Snapshot
  - Task Run
  - Git Worktree
  - Tool Adapter
- 可以依赖 `domain`。

## 2. 依赖规则

1. `controlplane -> domain`
2. `runtime -> domain`
3. `controlplane <-> runtime` 只能通过显式 port / command / event 交互。
4. 不允许把数据库表直接当作跨层公共 API。
5. 不允许让 task 直接绑定 agent；task 只能绑定 capability pack requirement。
6. 不允许让 agent 直接散绑 raw skill/tool；agent 以 capability pack 为主绑定单位。

## 3. 数据库五层真相

当前 `agentx_platform` schema 的 30 张表分成 5 层：

1. 平台资产层
   - `runtime_packs`
   - `tool_definitions`
   - `skill_definitions`
   - `skill_tool_bindings`
   - `capability_packs`
   - `capability_pack_runtime_packs`
   - `capability_pack_tools`
   - `capability_pack_skills`
   - `agent_definitions`
   - `agent_definition_capability_packs`
2. 固定流程定义层
   - `workflow_templates`
   - `workflow_template_nodes`
3. 流程编排运行层
   - `workflow_runs`
   - `workflow_run_node_bindings`
   - `workflow_run_events`
   - `workflow_node_runs`
   - `workflow_node_run_events`
4. 需求与人工介入层
   - `requirement_docs`
   - `requirement_doc_versions`
   - `tickets`
   - `ticket_events`
5. 规划与交付执行层
   - `work_modules`
   - `work_tasks`
   - `work_task_capability_requirements`
   - `work_task_dependencies`
   - `task_context_snapshots`
   - `agent_pool_instances`
   - `task_runs`
   - `task_run_events`
   - `git_workspaces`

## 4. 当前固定工作流

当前只允许一条内置 coding workflow：

1. 需求代理
2. 架构代理
3. 工单收件箱
4. 任务图
5. 工作代理管理器
6. 编码代理
7. 合并闸门
8. 验证代理

稳定规则：

1. 工作流结构固定，不开放自由注册和任意节点编排。
2. 允许替换部分 Agent 节点绑定。
3. 允许少量参数覆盖。
4. 允许自动代理模式开关。
5. 人类介入只能通过 `tickets` / `ticket_events`。
6. 顶层节点执行使用 `workflow_node_runs`。
7. 子任务执行使用 `task_runs`。
8. 每个 `task_run` 必须绑定 `READY` 的 `task_context_snapshot`。
9. Git worktree 真相在 `git_workspaces`，不能散落在 run payload 中。

## 5. 开发入口文档

开始动代码前，先看这些文档：

1. `docs/README.md`
2. `docs/architecture/01-three-layer-architecture.md`
3. `docs/architecture/02-fixed-coding-workflow.md`
4. `docs/architecture/03-domain-foundations.md`
5. `docs/database/01-table-layer-map.md`
6. `db/schema/agentx_platform_v1.sql`
7. `progress.md`

## 6. 修改前检查清单

1. 这次改动属于 `domain`、`controlplane`、`runtime` 哪一层。
2. 是否打破了“固定流程、动态 agent”的基本策略。
3. 是否错误地让 task 直接依赖 agent。
4. 是否错误地让 worker 直接找人，而不是走 ticket。
5. 是否把顶层节点执行和 task 执行混成一类 run。
6. 是否引入了可推导但冗余的状态字段。
7. 是否更新了 `progress.md` 并准备本地 git 提交。

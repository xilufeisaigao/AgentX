# AgentX Platform Progress

## 使用规则

1. 每完成一个小步骤，必须更新本文件中的状态、产出和验收结果。
2. 每完成一个小步骤，必须提交到本地 git 仓库。
3. 提交信息要明确说明本次完成了什么，不写笼统信息。
4. 当前优先级：
   - 先 `domain`
   - 再固定主流程
   - 最后 `controlplane`

## 当前路线

### P0 仓库基线

- 目标
  - 初始化本地 git 仓库，固定当前项目起点。
- 产出
  - 本地 git 仓库
  - 基线提交
- 验收方式
  - `git status` 工作区为空
  - 存在初始提交记录
- 当前状态
  - `DONE`
- 备注
  - 已完成提交：`chore: initialize local repository baseline`

### P1 文档与进度基线

- 目标
  - 固化实现顺序、阶段边界和每步验收方式。
- 产出
  - `progress.md`
  - `AGENTS.md` / `README.md` 对进度文档的引用
- 验收方式
  - 文档中能直接看到阶段顺序和验收标准
  - 后续任务可按文档更新状态
- 当前状态
  - `DONE`
- 备注
  - 已创建 `progress.md`，并在 `AGENTS.md`、`README.md` 增加引用。

### P2 包结构重构

- 目标
  - 把当前项目从试验性骨架调整为稳定三层结构。
- 范围
  - `domain`
  - `runtime`
  - `controlplane`
- 产出
  - 新包目录
  - 旧试验骨架清理
  - 分层入口说明
- 验收方式
  - 目录结构与文档一致
  - 不再保留和新规划冲突的旧包组织
- 当前状态
  - `DONE`
- 备注
  - 已移除旧试验骨架，建立三层新目录和后续实现落点。

### P3 Domain 骨架

- 目标
  - 先落地 `domain` 层，围绕固定主流程建立最小领域骨架。
- 范围
  - `shared`
  - `catalog`
  - `flow`
  - `intake`
  - `planning`
  - `execution`
- 产出
  - `model`
  - `policy`
  - `port`
  - 核心枚举和值对象
- 验收方式
  - `domain` 目录结构稳定
  - 关键对象和端口已能表达数据库五层真相
  - 不依赖 Web / MyBatis / Docker / LangGraph 具体实现
- 当前状态
  - `DONE`
- 备注
  - 已补齐 `shared / catalog / flow / intake / planning / execution` 六个切片下的 `model / policy / port` 领域骨架。
  - 已修正启动类中的过期配置引用，当前骨架可独立编译。
  - 验收通过：`./mvnw -q -DskipTests compile`
  - 已补领域基础语义：`AggregateRoot / ValueObject` 标记，以及 `ActorRef / WriteScope / JsonPayload` 三个实际值对象。
  - 已归档聚合根和值对象设计文档，作为后续状态机讨论的前置基线。
  - 语义收口验证通过：`./mvnw -q -DskipTests compile`、`AGENTX_DB_PASSWORD=*** ./mvnw -q test`

### P4 MyBatis 持久化骨架

- 目标
  - 为 `domain` 端口提供 MyBatis 适配层。
- 范围
  - mapper
  - repository
  - type handler
  - datasource / migration 对齐 MySQL
- 产出
  - MyBatis 包结构
  - 基础 mapper 与 repository
  - MySQL/Flyway 基础配置
- 验收方式
  - 应用能连上本地 MySQL
  - 能完成核心聚合的最小读写
- 当前状态
  - `DONE`
- 备注
  - 已切换到 `MyBatis + MySQL` 依赖基线，移除旧的 `Spring Data JDBC / PostgreSQL` 方向依赖。
  - 已在 `runtime.persistence.mybatis` 下补齐 `config / mapper / repository / typehandler` 骨架，并按 `catalog / flow / intake / planning / execution` 五个流程切片实现领域端口适配。
  - 为对齐表真相，已补齐 `Ticket / WorkTask / TaskContextSnapshot / TaskRun` 中用于持久化写入的关键必填字段。
  - 验收通过：`AGENTX_DB_PASSWORD=*** ./mvnw -q test`
  - 当前 `Flyway` 仅保留 MySQL 对齐配置，迁移脚本将在后续把现有 DDL 收敛为版本化迁移时再启用。

### P5 固定主流程应用骨架

- 目标
  - 打通 requirement -> ticket -> task -> task run 的主流程骨架。
- 产出
  - 启动 workflow run
  - requirement 版本流转
  - ticket 澄清闭环
  - task / dag 生成
  - task run / workspace 生成
- 验收方式
  - 能以一个最小样例跑通主流程
  - 状态流转和数据库真相一致
- 当前状态
  - `DONE`
- 备注
  - 已完成第一轮真相收口：
    - `WorkTaskStatus` 已加入 `DELIVERED`
    - `workflow / ticket / task run` 事件模型已补 `dataJson`
    - `FlowStore / IntakeStore / PlanningStore / ExecutionStore` 已补主流程所需读写契约
    - MyBatis mapper / repository 已补 `workflow_node_runs`、`workflow_run_events`、`agent_pool_instances`、`task_runs`、`git_workspaces` 等读写能力
  - 已完成 Runtime V1 固定应用服务：
    - `start`
    - `runUntilStable`
    - `answerTicket`
    - `getRuntimeSnapshot`
  - 已完成 LangGraph 顶层固定图：
    - `requirement`
    - `architect`
    - `ticket-gate`
    - `task-graph`
    - `worker-manager`
    - `coding`
    - `merge-gate`
    - `verify`
  - 已完成本地 fake runtime：
    - `LocalRequirementAgent`
    - `LocalArchitectAgent`
    - `LocalCodingAgent`
    - `LocalVerifyAgent`
    - `SyntheticWorkspaceService`
  - 已完成一次 clarification -> human answer -> resume 闭环
  - 已补运行实现文档与 deferred 文档，避免后续升级继续混入主链
  - 验收通过：`AGENTX_DB_PASSWORD=*** ./mvnw -q test`

### P6 Controlplane

- 目标
  - 最后补控制面能力。
- 产出
  - Agent 管理
  - Workflow 目录与查询
  - 进度、运行态和人工提请视图
- 验收方式
  - 核心 API 可以查询和操作主流程对象
- 当前状态
  - `PENDING`

## 下一阶段执行计划

### P5.1 真实执行链设计收口

- 目标
  - 先把固定主链从“fake 证明可行”收口到“真实执行接口已定型”。
- 范围
  - `runtime.application`
  - `runtime.orchestration`
  - `runtime.agentruntime`
  - `runtime.workspace`
- 产出
  - 固定主链各节点的真实输入输出契约
  - `worker-manager -> coding -> merge-gate -> verify` 的真实执行边界文档
  - `TaskRun / GitWorkspace / Ticket / WorkflowRun` 的协作规则补充
- 验收方式
  - 文档能明确回答：
    - agent 如何拿到上下文
    - capability / tool / runtime pack 如何进入执行环境
    - task run 结束后哪些证据进入 merge gate
    - 什么情况下回 architect，什么情况下直接失败
  - 不引入新的胶水 DTO 层
- 当前状态
  - `PENDING`

### P5.2 Docker 执行环境基线

- 目标
  - 让 worker 真正跑在可控的容器环境里，而不是本地 fake adapter 直接返回结果。
- 范围
  - `runtime.agentruntime.docker`
  - `runtime.support`
  - `runtime.persistence`
- 产出
  - Docker worker runtime 最小适配
  - 基础镜像/运行参数约定
  - task run 与 container 生命周期绑定
  - container 启动失败、退出码、stderr 的证据回写
- 验收方式
  - `worker-manager` 能为一个 `READY` task 拉起容器执行
  - `task_runs` 中可看到真实开始/结束/失败证据
  - 容器失败时可落成 `task_run_events`
- 当前状态
  - `PENDING`

### P5.3 真实 Git Worktree 分配

- 目标
  - 把当前 synthetic workspace 替换为真实 git worktree 分配。
- 范围
  - `runtime.workspace.git`
  - `runtime.application`
- 产出
  - repo root / branch / worktree 的真实分配策略
  - 每个 task run 独立 worktree
  - worktree 清理与复用规则
- 验收方式
  - 启动 task run 时能真实创建 worktree
  - `git_workspaces` 中记录真实路径、分支、base commit
  - task 结束后 workspace 状态能真实推进
- 当前状态
  - `PENDING`

### P5.4 Merge Gate 落地

- 目标
  - 把 merge-gate 从逻辑占位变成真实代码交付闸门。
- 范围
  - `runtime.workspace.git`
  - `runtime.application`
  - `runtime.agentruntime`
- 产出
  - 提交产物检查
  - 基础 diff/changed-files 校验
  - 合并尝试与冲突处理基线
  - merge 证据回写
- 验收方式
  - `DELIVERED` task 能进入真实 merge attempt
  - merge 成功后写回 `merge_commit`
  - merge 冲突时不会误标 `DONE`，而是回 architect 或转 ticket
- 当前状态
  - `PENDING`

### P5.5 DAG 与任务拆分可用化

- 目标
  - 先把 DAG 和任务拆分做到“可用”，不急着做复杂优化。
- 范围
  - `runtime.agentruntime.local`
  - `runtime.application`
  - `domain.planning`
- 产出
  - 多 task DAG 基线
  - 任务依赖与 `READY` 判定
  - capability requirement 与 agent instance 匹配基线
- 验收方式
  - 一个 workflow 至少能拆成 2-3 个 task
  - 依赖未满足的 task 不会被错误派发
  - 上游完成后下游 task 能自动进入 `READY`
- 当前状态
  - `PENDING`

### P5.6 Verify 与返工闭环

- 目标
  - 把 verify 从 fake acceptor 补成真正的交付闭环节点。
- 范围
  - `runtime.application`
  - `runtime.agentruntime`
  - `runtime.workspace`
- 产出
  - 验证命令/脚本执行基线
  - 验证失败后的返工路径
  - `DONE` 判定收口
- 验收方式
  - verify 不再只按开关返回结果
  - 失败能产生证据并回 architect
  - 通过后 `WorkTask -> DONE` 的条件清晰且可测
- 当前状态
  - `PENDING`

### P5.7 Supervisor 最小闭环

- 目标
  - 补齐 runtime 监督能力，避免 worker 掉线后流程失真。
- 范围
  - `runtime.support`
  - `runtime.application`
  - `runtime.persistence`
- 产出
  - heartbeat 更新
  - lease timeout 检测
  - worker 失联后的 run 终止/重派发/升级基线
- 验收方式
  - 能模拟一个 worker 失联场景
  - 超时后 `task_runs`、`agent_pool_instances`、`workflow_runs` 状态一致
  - 必要时会升级给 architect/ticket，而不是静默挂死
- 当前状态
  - `PENDING`

## 本轮任务

- 任务
  - 输出 Runtime 后续补齐计划，并按“先核心流程、后控制面”的顺序冻结下一阶段优先级。
- 验收
  - `progress.md` 已新增 P5.1-P5.7 的执行计划
  - 执行顺序已体现：
    - 先真实执行链设计
    - 再 Docker / Git Worktree / Merge Gate
    - 再 DAG 可用化 / Verify / Supervisor
  - 当前不提前启动 controlplane / 展示面
- 结果
  - 待执行

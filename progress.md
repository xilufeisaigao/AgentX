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
  - `IN_PROGRESS`
- 备注
  - 已完成第一轮真相收口：
    - `WorkTaskStatus` 已加入 `DELIVERED`
    - `workflow / ticket / task run` 事件模型已补 `dataJson`
    - `FlowStore / IntakeStore / PlanningStore / ExecutionStore` 已补主流程所需读写契约
    - MyBatis mapper / repository 已补 `workflow_node_runs`、`workflow_run_events`、`agent_pool_instances`、`task_runs`、`git_workspaces` 等读写能力
  - 当前验收通过：`./mvnw -q -DskipTests compile`

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

## 本轮任务

- 任务
  - 完善 `AGENTS.md`，固化开发规范、文档索引、项目结构索引，以及注释/DTO/异常处理规则。
- 验收
  - `AGENTS.md` 已可作为编码入口规范
  - 已补入文档引用和项目结构索引
  - 已补入重点注释规范
  - 已补入反胶水 / 反 DTO 膨胀规范
  - 已补入异常快速失败规范
  - 完成一次清晰的本地 git 提交
- 结果
  - 已验证：`git diff --check`
  - 已自检：规范内容覆盖注释、DTO、异常、文档索引、项目结构 5 个目标
  - 待提交

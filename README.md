# AgentX Platform

这是一个新的 greenfield Agent 平台内核项目。

目标不是继续在旧控制面上堆编排逻辑，而是先建立一个更清晰的平台骨架：

1. `domain`
   - 平台核心对象和边界。
   - 例如 Agent、Workflow Template、运行时策略。
2. `controlplane`
   - 平台注册、查询、配置、Agent 管控 API。
   - 重点是“固定工作流模板 + 动态 Agent 注册与控制”。
3. `runtime`
   - 固定主链运行时和执行适配层。
   - 当前已经用 LangGraph4j 跑通 Runtime V1 的固定代码流程闭环。

## 当前约束

第一阶段平台策略明确如下：

1. 工作流模板不允许用户自由拼装。
2. 只开放少量扩展点：
   - Agent 节点替换
   - 参数覆盖
   - 自动代理模式开关
3. 核心可扩展面放在 Agent：
   - 注册
   - 能力
   - 池化
   - 准入与控制

## 当前已实现内容

1. Spring Boot Maven 脚手架
2. MyBatis + MySQL 持久化基线
3. 三层包结构骨架
4. 固定代码工作流模板与绑定真相
5. Runtime V1 应用服务
6. LangGraph 顶层固定图
7. 本地 fake requirement / architect / coding / verify adapters
8. MySQL 集成测试基线与 Runtime V1 端到端测试

## 内置代码工作流策略

平台当前只内置一条代码交付流程模板：

- 需求代理
- 架构代理
- 工单闸口
- 任务图
- 工作代理管理器
- 编码代理
- 合并闸门
- 验证代理

这条工作流是固定结构，但允许替换部分 Agent 绑定。

## 文档入口

平台结构、固定工作流和数据库分层图已经归档到 `docs/`：

1. `docs/README.md`
2. `docs/architecture/01-three-layer-architecture.md`
3. `docs/architecture/02-fixed-coding-workflow.md`
4. `docs/architecture/04-state-machine-layers.md`
5. `docs/runtime/01-runtime-v1-implementation.md`
6. `docs/deferred/01-runtime-v1-deferred.md`
7. `docs/database/01-table-layer-map.md`
8. `progress.md`

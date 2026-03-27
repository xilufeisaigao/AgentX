# AgentX Platform

这是一个新的 greenfield 平台内核项目。

目标不是继续在旧控制面上堆编排逻辑，而是先建立一个更清晰的平台骨架：

1. `domain`
   - 平台核心对象和边界。
   - 例如 Agent、Workflow Template、运行时策略。
2. `controlplane`
   - 平台注册、查询、配置、Agent 管控 API。
   - 重点是“固定工作流模板 + 动态 Agent 注册与控制”。
3. `runtime`
   - 工作流运行时适配层。
   - 这里未来会逐步接入 LangGraph4j、LangChain4j、Docker Agent Runtime、Checkpoint、RAG。

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

## 当前已初始化内容

1. Spring Boot Maven 脚手架
2. LangGraph4j / LangChain4j 依赖
3. Postgres / Flyway / OpenAPI 基础依赖
4. 三层包结构骨架
5. 内置代码工作流模板目录
6. Agent 注册与目录查询 API 骨架

## 第一阶段接口

1. `GET /api/v1/catalog/agents`
2. `POST /api/v1/catalog/agents`
3. `GET /api/v1/catalog/workflows`
4. `GET /api/v1/catalog/kernel-policy`

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
4. `docs/database/01-table-layer-map.md`
5. `progress.md`

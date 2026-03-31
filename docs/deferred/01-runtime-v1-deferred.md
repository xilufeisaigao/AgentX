# Runtime V1 Deferred 清单

本文只记录当前明确不做、但后续会影响平台演进的事项，避免它们重新渗进主链代码。

## 1. 模型质量与推理增强

1. Prompt 管理和版本化治理
2. judge-based 质量评测与 rubric 评分
3. end-to-end artifact quality scoring
4. benchmark / leaderboard / regression gate
5. online shadow eval / A-B eval
6. 长上下文裁剪与上下文压缩优化
7. 多模型路由与成本控制策略

## 2. 更高阶的任务规划能力

1. 多任务自动拆分与重规划
2. architect 自动补图和复杂 DAG 优化
3. 基于能力/风险/成本的动态任务路由
4. 更细粒度的返工分类与自动升级策略

## 3. 更强的运行时形态

1. K8s runtime adapter
2. 容器资源配额与隔离治理
3. 可复用 agent pool / warm pool
4. 镜像构建、发布和版本治理
5. 多 runtime backend 路由

说明：

1. Docker CLI baseline 已完成，不再列为 deferred。
2. 单仓 Git worktree baseline 已完成，不再列为 deferred。
3. 进程内 supervisor baseline 已完成，不再列为 deferred。
4. 基础版四个 agent kernel 已完成，不再列为 deferred。
5. 上下文编译中心与本地 lexical/symbol RAG baseline 已完成，不再列为 deferred。
6. 基础版离线 agent eval baseline 已完成，不再列为 deferred。
7. Eval Center V1 文件优先报告链路已完成，不再列为 deferred。

## 4. 更复杂的代码工作区能力

1. workflow 级或 task 级多仓路由
2. 跨仓库 merge / verify
3. 更复杂的冲突自动修复
4. workspace 证据留存治理
5. 更细粒度的 cleanup 审计与回收策略

## 5. 平台控制面

1. workflow / ticket / task / run 查询接口
2. agent definition 列表、详情和变更历史视图
3. agent pool / runtime instance 管理视图
4. 人工提请处理中心 UI
5. 更细粒度的审批、治理和运维入口

说明：

1. command-side controlplane API baseline 已完成，不再列为 deferred。
2. `AgentDefinition` 的创建、启停和 capability 绑定更新已具备正式入口。
3. `AgentPoolInstance` 仍然不是人工长期管理资产，相关可视化和运维视图继续 deferred。

## 6. 可观测性与治理

1. token 成本统计
2. agent 执行效果监控
3. judge-based 或在线化代码质量评测链路
4. 指标、日志、审计面板
5. 运行 SLA / 错误预算

## 7. 扩展能力

1. 多模板固定工作流
2. capability pack 自动推荐
3. architect 自动建 agent 入池
4. 更丰富的 verify contract 类型
5. 更细的运行策略参数控制面

## 8. 向量化与检索效果升级

1. embeddings / vector store
2. hybrid recall / rerank
3. 更细粒度 chunk 策略
4. requirement / ticket / logs 的语义召回
5. docs / schema / code 的跨源排序优化

## 10. Eval Center 后续增强

1. `scorecard.json` 的 regression gate 自动判定
2. judge-based report summarization
3. 在线评测触发与定时回归运行
4. eval artifacts 的 query API / UI 展示
5. token / cost 真实统计接入

说明：

1. 第一版只要求文件优先 artifact，不要求 DB 持久化。
2. 第一版只要求报告完整可追溯，不要求 AI 自动裁判。

## 9. 当前原则

这些能力都应该以后续独立模块或适配器的形式接入，而不是回头污染 Runtime V1 主链。

当前主链只接受两类增量：

1. 直接支撑 `requirement -> architect -> ticket-gate -> task-graph -> worker-manager -> coding -> merge-gate -> verify` 的必要逻辑
2. 明确落在现有边界上的替换型适配器

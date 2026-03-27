# Runtime V1 Deferred 清单

本文只记录本轮明确不做、但后续会影响平台演进的事项，避免它们重新渗进主链代码。

## 1. 真实模型与推理增强

1. 真实 LLM 接入
2. Prompt 管理和版本化
3. RAG / 检索增强
4. 长上下文裁剪与上下文压缩

## 2. 真实执行运行时

1. Docker agent runtime
2. 真实进程隔离与资源配额
3. tool sandbox
4. runtime 镜像生命周期管理

## 3. 真实代码工作区

1. 真实 Git worktree
2. 真实分支创建、提交、diff、merge
3. merge conflict 处理
4. 回滚与清理策略

## 4. 运行监督与恢复

1. 后台 watchdog / scheduler
2. heartbeat timeout 检查
3. lease 续租失败恢复
4. worker 崩溃自动回收与重派发
5. 自动重规划

## 5. 平台控制面

1. HTTP / controlplane API
2. agent 注册与下线
3. agent pool 管理视图
4. workflow / ticket / task / run 查询接口
5. 人工提请处理中心 UI

## 6. 可观测性与治理

1. token 成本统计
2. agent 执行效果监控
3. 代码质量评测链路
4. 指标、日志、审计面板
5. 运行 SLA / 错误预算

## 7. 扩展能力

1. 多 agent autoscaling
2. capability pack 自动推荐
3. architect 自动建 agent 入池
4. 更多固定模板
5. 控制面上的参数化运行策略

## 8. 当前原则

这些能力都应该以后续独立模块或适配器的形式接入，而不是回头污染 Runtime V1 主链。

当前主链只接受两类增量：

1. 直接支撑 `requirement -> architect -> ticket-gate -> task-graph -> worker-manager -> coding -> merge-gate -> verify` 的必要逻辑
2. 明确落在现有边界上的替换型适配器

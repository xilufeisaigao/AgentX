# AgentX 学习路径

这份学习路径是给“项目已经有点失控，需要重新夺回控制权”的场景准备的。
原则很简单：先掌握真实运行面，再掌握链路，再掌握模块，再做局部工程化升级。

## 30 分钟版本

按下面顺序读：

1. [00-learning-progress.md](00-learning-progress.md)
2. [README.md](README.md)
3. [current-state/02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
4. [architecture/03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
5. [05-code-index.md](05-code-index.md)

读完你至少应该能回答：

- 项目主要跑在 Docker 里，不是本地手搓运行。
- 一个 session 是如何从需求确认一路自动推进到 delivery clone 的。
- 哪些数据在 MySQL，哪些证据在 git，哪些上下文在 `/agentx/runtime-data`。

## 2 小时版本

在 30 分钟版本基础上继续：

1. 看 [reference/common-commands.md](reference/common-commands.md)，亲自跑一次 `docker compose ps`、`GET /progress`、`GET /run-timeline`。
2. 看 [architecture/04-runtime-artifacts.md](architecture/04-runtime-artifacts.md)，把 host bind mount、Docker volume、session repo 路径和 bare clone 路径都认清。
3. 看 [modules/07-process.md](modules/07-process.md)、[modules/14-execution.md](modules/14-execution.md)、[modules/08-query.md](modules/08-query.md)。

读完你应该能回答：

- 为什么系统不是“一个 API 调到底”，而是事件监听器加后台 scheduler 在推进。
- 为什么 `canCompleteSession` 不是 `sessions` 表字段，而是查询层的聚合结果。
- 为什么中间某个 run 失败了，系统仍可能自动恢复并最终闭环。

## 5 天版本

建议每天只抓一个主轴，不要一口气扫全仓。

### Day 1: 运行面

读：

- [current-state/02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
- [deployment/docker-runtime.md](deployment/docker-runtime.md)
- [architecture/04-runtime-artifacts.md](architecture/04-runtime-artifacts.md)

目标：

- 能自己判断“栈是否起来了”“repo 在哪”“context 在哪”“clone repo 在哪”。

### Day 2: 会话到需求

读：

- [modules/09-session.md](modules/09-session.md)
- [modules/10-requirement.md](modules/10-requirement.md)
- [modules/18-ticket.md](modules/18-ticket.md)

目标：

- 看清 session 创建、需求草拟、需求确认、ARCH_REVIEW ticket 生成这条前半链。

### Day 3: 架构到任务

读：

- [modules/07-process.md](modules/07-process.md)
- [modules/11-planning.md](modules/11-planning.md)
- [modules/12-contextpack.md](modules/12-contextpack.md)

目标：

- 看清 architect 自动化、任务拆解、上下文编译、context snapshot 刷新。

### Day 4: Worker 到 Run

读：

- [modules/13-workforce.md](modules/13-workforce.md)
- [modules/14-execution.md](modules/14-execution.md)
- [modules/15-workspace.md](modules/15-workspace.md)

目标：

- 看清 worker 何时被自动 provision，任务如何 claim，run 如何持有 lease，worktree 怎么分配。

### Day 5: Merge、交付、查询

读：

- [modules/16-mergegate.md](modules/16-mergegate.md)
- [modules/17-delivery.md](modules/17-delivery.md)
- [modules/08-query.md](modules/08-query.md)

目标：

- 看清 DELIVERED 到 DONE 之间真正发生了什么，clone repo 是怎么发布的，前端读到的进度视图从哪拼出来。

## 日常小步升级法

后面每天做一点点时，建议固定节奏：

1. 先选一个具体问题。
   例子：为什么 verify 失败后还能恢复，或者为什么 worker 会挑 `WRK-BOOT-JAVA-DB`。
2. 先查 [05-code-index.md](05-code-index.md)。
3. 再读对应模块文档。
4. 最后只改一个小切口。
   例子：补日志、补约束、补测试、收敛分支逻辑。

不要一开始就做“大重构”。
先让每个问题都能稳定定位到类、方法、表、目录和命令，再逐步工程化。

## 每日学习闭环

如果你打算每天都用新的 AI 窗口继续学，建议固定口令：

1. 开始时说：`开始学习` 或 `继续学习`
2. 中间追问：`这个没懂`、`展开这个方法`、`继续`
3. 结束时说：`结束学习`

理想行为应该是：

1. agent 先读 [00-learning-progress.md](00-learning-progress.md)
2. 给出今日目标、今日流程图和少量代码
3. 每次只讲一个小轮次
4. 默认用英文主讲，难词和术语补中文括注，复杂段落后补完整中文翻译
5. 你说 `结束学习` 后，更新：
   - [19-study-session-log.md](19-study-session-log.md)
   - [20-concept-and-interview-bank.md](20-concept-and-interview-bank.md)

## 适合优先做的工程化主题

如果你接下来每天都想做一点，优先顺序建议是：

1. 可观测性
   统一 run、task、ticket、session 的日志关联键和排查手册。
2. 查询面可解释性
   明确哪些字段来自表，哪些字段来自聚合逻辑，避免“看起来像表字段”的错觉。
3. 调度器治理
   梳理 scheduler 的轮询频率、幂等保证、失败重试和并发竞争。
4. 工作区与 git 生命周期
   把 worktree 清理、merge candidate、delivery tag、clone publish 的边界补齐。
5. 代码收敛
   逐步收拾 process 模块中容易继续膨胀的编排逻辑。

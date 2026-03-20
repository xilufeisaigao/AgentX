# AgentX 学习进度总表

这份文档是给“新开一个 AI 窗口，只说一句 `开始学习` 或 `继续学习`”的场景准备的。

目标：

1. 让新的 agent 知道现在该讲到哪里。
2. 让学习过程按课堂式微轮次推进，而不是一次性把整个模块讲完。
3. 让每轮都贴少量真实代码，并补一份带教学注释的代码。
4. 让每天结束时自动做复盘、概念整理和面试题沉淀。
5. 让学习输出默认采用英文主讲模式，同时配中文辅助。

## 微轮次学习协议

新的 agent 必须按下面的节奏讲，不要一口气倾倒太多内容。

1. 每次回答只推进一个小轮次。
2. 一个小轮次通常只包含：
   - 1 个小 checkpoint
   - 1 到 3 个函数 / 方法
   - 或 1 个很小的类
3. 每轮开始先给：
   - 今日学习目标
   - 今日流程图
   - 本轮在流程图里的位置
4. 每轮如果出现新概念，先补“概念垫片”。
   例子：Docker、Redis、Elasticsearch、LangChain、DAG、RAG、Scheduler、Listener、Worktree。
5. 每轮讲代码时，必须同时给：
   - 原始源码
   - 带教学注释的源码
6. 每轮结束后要停下来，等待用户说：
   - `开始学习`
   - `继续学习`
   - `继续`
   - `这个没懂`
   - `展开这个方法`
   - `结束学习`
7. 如果用户说 `结束学习`，agent 必须：
   - 总结今日学习情况
   - 总结今日关键概念
   - 记录用户反复问的问题
   - 产出几道面试题
   - 更新本文件与复习文档

## 语言模式

默认使用英文主讲。

具体规则：

1. 正文解释优先用英文。
2. 较难词汇和专业术语第一次出现时，要在后面加中文括注。
   例子：`orchestration (编排)`、`scheduler (调度器)`、`DAG (有向无环图)`。
3. 如果一段解释比较复杂，或者连续 2 到 3 句以上，后面要补完整中文翻译。
4. Mermaid 图里的关键标签也要补中文提示。
5. 目标不是华丽英文，而是清晰、可学、便于你练习的工程英语。

## 今日会话状态

- 当前学习日期: 2026-03-19
- 当日状态: ready_to_start
- 今日主题: Phase 0 建立全局运行面认知
- 今日主文档: [02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
- 今日流程图主题: Docker 运行面 -> 真实闭环样本 -> 运行时产物目录 -> 总链路入口
- 当前微轮次: S1
- 当前 checkpoint: Docker 运行面、真实闭环样本、运行时产物目录
- 下一轮目标: 先讲清系统现在是怎么跑起来的，再进入 [03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
- 当前等待动作: 用户发出 `开始学习`、`继续学习` 或针对当前内容提问

## 学习主线

### Phase 0: 基础运行面与全链路

- [ ] R1: [02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
- [ ] R2: [03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
- [ ] R3: [04-runtime-artifacts.md](architecture/04-runtime-artifacts.md)
- [ ] R4: [05-code-index.md](05-code-index.md)
- [ ] R5: [06-module-map.md](modules/06-module-map.md)

### Phase 1: 模块学习顺序

- [ ] M1: [07-process.md](modules/07-process.md)
- [ ] M2: [08-query.md](modules/08-query.md)
- [ ] M3: [09-session.md](modules/09-session.md)
- [ ] M4: [10-requirement.md](modules/10-requirement.md)
- [ ] M5: [11-planning.md](modules/11-planning.md)
- [ ] M6: [12-contextpack.md](modules/12-contextpack.md)
- [ ] M7: [13-workforce.md](modules/13-workforce.md)
- [ ] M8: [14-execution.md](modules/14-execution.md)
- [ ] M9: [15-workspace.md](modules/15-workspace.md)
- [ ] M10: [16-mergegate.md](modules/16-mergegate.md)
- [ ] M11: [17-delivery.md](modules/17-delivery.md)
- [ ] M12: [18-ticket.md](modules/18-ticket.md)

### Phase 2: 工程化复盘

- [ ] E1: 可观测性与日志关联键
- [ ] E2: query 可解释性与“表字段 / 聚合字段”分离
- [ ] E3: scheduler / listener / orchestration 收敛
- [ ] E4: workspace / mergegate / delivery 生命周期收敛
- [ ] E5: Python 重构候选链路选择

## 每个模块的默认微轮次模板

如果用户没有另行指定，默认按这个节奏讲：

1. Step A: 今日目标、今日流程图、模块边界、上下游依赖
2. Step B: 第一小段代码，通常是 controller / listener / scheduler / facade
3. Step C: 第二小段代码，通常是 application service / use case / domain flow
4. Step D: 持久化、事件、query 投影、与其他模块的交接
5. Step E: 这个模块最容易腐化的点，以及下一步工程优化方向

`process`、`execution`、`query` 这三个模块默认要拆得更细，不要一次塞太多。

## 结束学习时必须更新的文档

1. [19-study-session-log.md](19-study-session-log.md)
2. [20-concept-and-interview-bank.md](20-concept-and-interview-bank.md)
3. 本文件当前指针

## 轮次日志

| 日期 | 微轮次 | 主题 | 讲到哪里 | 是否完成 | 用户高频问题 | 下一轮 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-03-19 | R0 | 学习系统初始化 | 建立学习主线、编号文档、更新 skill | yes | 暂无 | R1: runtime audit |

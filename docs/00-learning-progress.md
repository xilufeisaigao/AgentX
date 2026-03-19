# AgentX 学习进度总表

这份文档是给“新开一个 AI 窗口，只说一句 `继续学习`”的场景准备的。

目标：

1. 让新的 agent 知道现在该讲到哪里。
2. 让学习过程按轮次推进，而不是一次性把整个模块讲完。
3. 让每轮都能贴真实代码，并给出教学注释。

## 使用规则

1. 新窗口如果只收到“继续学习”，必须先读本文件。
2. 新窗口如果收到“讲解某个模块”，也要先读本文件，再决定是切换目标还是继续当前主线。
3. 每次回答只推进一轮。
4. 每轮必须至少包含：
   - 当前所处位置回顾
   - 一张模块图或链路图
   - 一段真实源码
   - 一段带教学注释的源码
   - 这段代码在全链路里的作用
   - 下一轮会讲什么
5. 每轮结束后，agent 要更新本文件中的“当前指针”和“轮次日志”。
6. 如果这一轮主要是在回答用户追问，不要强行推进到下一个 checkpoint；先把当前 checkpoint 讲透。

## 当前指针

- 当前阶段: Phase 0 基础运行面与总链路
- 当前主题: 先建立项目全局控制感，再进入模块深挖
- 当前文档: [02-runtime-audit-2026-03-17.md](current-state/02-runtime-audit-2026-03-17.md)
- 当前模块: `none`
- 当前轮次: `R1`
- 当前 checkpoint: Docker 运行面、真实闭环样本、运行时产物目录
- 下一轮目标: 先讲清系统现在是怎么跑起来的，再进入 [03-end-to-end-chain.md](architecture/03-end-to-end-chain.md)
- 如果用户只说“继续学习”: 就从这里开始

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

## 每个模块的默认讲解轮次

如果用户没有另行指定，默认按这个节奏讲：

1. Round A: 模块图、边界、上下游依赖、主入口
2. Round B: 第一段关键代码，通常是 controller / listener / scheduler / facade
3. Round C: 第二段关键代码，通常是 application service / use case / domain flow
4. Round D: 持久化、事件、query 投影、与其他模块的交接
5. Round E: 这个模块最容易腐化的点，以及下一步工程优化方向

如果某个模块比较复杂，例如 `process`、`execution`、`query`，可以拆出更多轮。

## 轮次日志

| 日期 | 轮次 | 主题 | 讲到哪里 | 是否完成 | 备注 | 下一轮 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-03-19 | R0 | 学习系统初始化 | 建立学习主线、编号文档、更新 skill | yes | 从下一轮开始按“继续学习”推进 | R1: runtime audit |

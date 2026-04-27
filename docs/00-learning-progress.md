# AgentX 面试学习进度总表

这份文档是给“新开一个 AI 窗口，只说一句 `开始学习` 或 `继续学习`”的场景准备的。

目标：

1. 让新的 agent 知道现在该讲到哪里。
2. 让学习过程按面试主题微轮次推进，而不是按源码目录硬扫。
3. 每轮都要落到一小段真实代码。
4. 每轮都要给一道面试题和参考回答。
5. 默认全中文，不再做英语练习。

## 微轮次学习协议

新的 agent 必须按下面的节奏讲，不要一口气倾倒太多内容。

1. 每次回答只推进一个小轮次。
2. 一个小轮次通常只包含：
   - 1 个面试主题
   - 1 个小 checkpoint
   - 1 到 3 个函数 / 方法
   - 或 1 个很小的类
3. 每轮固定顺序：
   - 先说简历上怎么写
   - 再说面试官为什么会问
   - 再说项目里怎么设计
   - 再贴真实代码和带注释代码
   - 最后给 1 道面试题和 1 份参考回答
4. 如果出现新概念，先补概念，再讲代码。
5. 每轮结束后要停下来，等待用户说：
   - `开始学习`
   - `继续学习`
   - `继续`
   - `这个没懂`
   - `展开这个方法`
   - `再来一道`
   - `结束学习`

## 今日会话状态

- 当前学习日期: 2026-03-26
- 当日状态: waiting_user
- 今日主题: Phase 0 面试主线稳定化与题库机制补齐
- 今日主文档: [interview/agentx-00-项目总述与简历写法.md](interview/agentx-00-项目总述与简历写法.md)
- 当前微轮次: I0-R1
- 当前 checkpoint: 已完成 30 秒项目陈述，当前能用“控制面定位 + 五个机制 + 四类风险”讲清项目开场
- 下一轮目标: I0-R2 2 分钟总体架构，串起 `session -> requirement -> planning -> context -> execution -> mergegate -> delivery`
- 当前等待动作: 用户发出 `继续学习`、`继续`、`这个没懂`、`展开这个方法`，或直接切到 `记忆` / `RAG` / `React` / `调度` / `HITL`

## 学习主线

### Phase 0: 项目总述

- [x] I0-R1: 30 秒项目陈述
- [ ] I0-R2: 2 分钟总体架构
- [ ] I0-R3: 简历上怎么诚实描述实现边界

### Phase 1: Agent 架构与状态机

- [ ] I1-R1: 为什么是控制面，不是脚本拼接
- [ ] I1-R2: 为什么用模块化单体 + DDD 分包
- [ ] I1-R3: `work_tasks / task_runs / tickets` 三条状态机怎么拆
- [ ] I1-R4: Spring Event + process 为什么够用

### Phase 2: 记忆、上下文与 RAG

- [ ] I2-R1: 记忆模块怎么设计
- [ ] I2-R2: 为什么聊天记录不能当记忆
- [ ] I2-R3: `task_context_snapshots` 怎么工作
- [ ] I2-R4: RAG 为什么是 semantic + lexical fallback
- [ ] I2-R5: `source_fingerprint` 和 `READY/STALE` 怎么防漂移

### Phase 3: Worker、调度与工具调用

- [ ] I3-R1: worker 为什么要有 toolpack
- [ ] I3-R2: claim / lease / heartbeat 怎么保证可恢复
- [ ] I3-R3: Git worktree 为什么比 clone 合适
- [ ] I3-R4: 工具调用、写权限和沙箱怎么控边界

### Phase 4: HITL 与工单化协同

- [ ] I4-R1: `NEED_DECISION / NEED_CLARIFICATION` 为什么不能直接问用户
- [ ] I4-R2: ticket 事件链怎么形成可审计事实
- [ ] I4-R3: 用户回复后为什么要先刷 context 再恢复 run

### Phase 5: 交付闭环与一致性

- [ ] I5-R1: 为什么 `DELIVERED != DONE`
- [ ] I5-R2: Merge Gate 为什么要做 `rebase -> VERIFY -> ff merge`
- [ ] I5-R3: Git 物理事实和数据库状态怎么对齐

### Phase 6: React 控制台与查询聚合

- [ ] I6-R1: React 在这个项目里解决了什么
- [ ] I6-R2: `useMissionRoom` 为什么是控制台核心 hook
- [ ] I6-R3: 为什么 query 聚合层比前端直查表更重要

### Phase 7: 高频开放题

- [ ] I7-R1: 这项目如果继续做，下一步怎么演进
- [ ] I7-R2: 怎么评估 Agent 系统的效果与稳定性
- [ ] I7-R3: 这项目最大的工程边界是什么

## 默认输出模板

如果用户没有另行指定，默认按这个结构讲：

1. `本轮主题`
2. `简历上怎么说`
3. `面试官为什么会问`
4. `项目里怎么设计`
5. `关键代码`
6. `带教学注释的代码`
7. `你要记住的回答骨架`
8. `本轮面试题`
9. `参考回答`
10. `下一轮会接什么`

## 结束学习时必须更新的文档

1. [19-study-session-log.md](19-study-session-log.md)
2. [20-concept-and-interview-bank.md](20-concept-and-interview-bank.md)
3. [21-interview-question-ledger.md](21-interview-question-ledger.md)
4. 本文件当前指针

## 轮次日志

| 日期 | 微轮次 | 主题 | 讲到哪里 | 是否完成 | 用户高频需求 | 下一轮 |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-03-25 | I0-R0 | 学习系统重构 | 把学习系统从英语课堂改成面试模式，重建文档入口和主题树 | yes | 只保留面试有用文档；每轮要有一题一答；记忆/RAG/React 要独立成主题 | I0-R1: 30 秒项目陈述 |
| 2026-03-26 | I0-R0.5 | 文档收缩与题库升级 | 删掉模块手册和运行手册，保留 8 讲主线、证据文档、总题库和补题账本；skill 支持每轮自动落题 | yes | docs 继续瘦身；每讲结束自动记题；后续外部面经题要能回填 | I0-R1: 30 秒项目陈述 |
| 2026-03-26 | I0-R1 | 30 秒项目陈述 | 用 `SessionCommandService.createSession -> SessionCreatedEvent -> SessionBootstrapInitProcessManager.handle` 证明项目从入口开始就是事件驱动控制面，而不是一次性 prompt 调用 | yes | 项目开场要先讲系统定位，再讲控制机制，最后再讲解决的工程风险 | I0-R2: 2 分钟总体架构 |

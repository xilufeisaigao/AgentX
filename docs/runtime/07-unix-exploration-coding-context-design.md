# Unix Exploration Coding Context Design

本文描述 AgentX 下一阶段对 coding agent 上下文与代码探索模型的目标方案。

状态说明：

1. 本文是新的目标设计稿，当前状态为“设计完成，代码待实现”。
2. 当前代码真相仍然以 `docs/runtime/03-context-compilation-center.md` 与 `docs/runtime/04-local-rag-and-code-indexing.md` 为准。
3. 本文会替代“继续把 coding 主链升级成更重的代码 RAG”这一方向。
4. 结构化事实层继续保留；被替换的是“代码片段主要由平台预检索注入”的模式。

## 1. 目标

我们希望把 coding agent 从“平台先给一包 repo retrieval，再让模型在有限工具里继续补读”改成：

1. 结构化事实上下文继续由平台统一编译。
2. 代码探索改为 agent 基于 Unix 类工具主动完成。
3. 读权限默认足够大，优先允许在当前 workspace 内自由探索。
4. 写权限继续严格受任务边界约束，不因为读权限放宽而一起放宽。
5. 一旦实现需要突破当前写边界，不偷偷放行，而是回到 blocker / architect 重规划。
6. coding prompt 需要显式知道当前运行环境，例如 Linux shell、PowerShell、runtime image、可用工具族。

## 2. 非目标

本轮目标方案不做这些事：

1. 不取消结构化事实层。
2. 不把 requirement / ticket / task / run / workspace 真相改成自由记忆。
3. 不让 coding agent 直接拥有无限写权限。
4. 不把任意 shell 放开成“模型随便拼任何命令”。
5. 不把 verify 重新退化成无护栏的自由探索阶段。

## 3. 顶层边界

新的 coding 上下文仍然沿当前统一入口扩展：

`ContextCompilationCenter -> FactRetriever -> CompiledContextPack`

但对 coding pack 做一个关键调整：

1. `FactRetriever`
   - 继续只取结构化事实
   - 继续走精确查询
2. `Code Retrieval`
   - 不再默认向 coding pack 注入 repo code snippets
   - repo / docs / schema 的探索改由 agent 运行期主动完成
3. `ContextCompilationCenter`
   - 继续负责组装结构化事实与运行守卫
   - 对 coding pack 不再把代码 RAG 当作主路径

也就是说，保留“统一上下文编译中心”，但把“代码内容如何进入模型”从预检索改成工具探索。

## 4. Coding Pack 应包含什么

新的 coding pack 继续保留这些高价值内容：

### 4.1 工作流与任务背景

让 agent 清楚：

1. 当前需求背景是什么。
2. 当前 workflow 走到了哪一步。
3. 当前 task 的目标、依赖、上游任务、已有 blocker 是什么。
4. 之前这个 task / run / workspace 发生过什么。

### 4.2 任务边界

让 agent 清楚：

1. 这次任务的写域是什么。
2. 推荐优先探索哪些目录。
3. 哪些路径虽然可读，但不应随意改写。
4. 如果要跨出当前写域，需要触发什么升级路径。
5. 当前有哪些 sibling task 正在并发推进，它们的写域和状态分别是什么。

这里需要把“推荐探索范围”和“允许写入范围”显式拆开：

1. `explorationRoots`
   - 提供给 agent 的初步探索范围
   - 通常应大于写域
2. `writeScopes`
   - 真正允许修改的边界
   - 必须是硬限制

### 4.3 运行环境事实

这部分是本轮新增重点，coding agent 不应再靠猜：

1. 当前运行环境属于 Linux shell 还是 PowerShell。
2. 当前 runtime image / runtime profile 是什么。
3. 工作目录、workspace root、仓库根目录是什么。
4. 当前可用的 Unix 工具族是什么，例如 `rg`、`grep`、`find`、`fd`、`sed`、`head`、`tail`、`git`。
5. 如果某些工具不可用，应明确告诉 agent 回退到哪组工具。

### 4.4 权限与升级规则

coding pack 里应明确告诉 agent：

1. 当前读权限边界。
2. 当前写权限边界。
3. 当前允许执行的命令类别。
4. 哪类动作会直接被拒绝。
5. 当前有哪些已批准资源、外部集成契约或环境绑定可以直接复用。
6. 哪类动作不应重试，而应创建 blocker 并交给审批处理中心或 architect 处理。

## 5. 从“代码 RAG”切到“Unix 探索”的原则

### 5.1 为什么不再把代码 RAG 作为 coding 主路径

代码任务里，模型更需要的是：

1. 自己确认目录结构。
2. 自己逐步缩小文件范围。
3. 自己做字符串搜索、符号搜索、git 状态确认。
4. 在读到足够证据后再修改。

如果平台预先塞一批 retrieval snippets，容易出现：

1. 模型被过早引导到错误文件。
2. 当前 task 不需要的代码噪音占据窗口。
3. “已经给了 snippets”与“仍然要自己确认仓库真相”之间角色冲突。
4. workflow overlay、repo base、docs/schema 混排后，agent 不易分辨什么是当前最可信的一手信息。

因此新的方向是：

1. requirement / architect / verify 仍可保留各自需要的 retrieval 或证据摘要。
2. coding 阶段默认不再注入 repo code snippets。
3. coding 需要的仓库知识主要通过运行期工具探索获得。

### 5.2 结构化事实层继续保留

这并不是回到“纯 ReAct + 任意 shell”。

平台仍然要提前给 agent：

1. 任务意图
2. 依赖与 blocker
3. 写域
4. 推荐探索范围
5. 环境类型
6. 权限规则
7. 可用工具清单

也就是：

结构化事实负责“告诉 agent 应该往哪里看、什么不能碰”；
Unix 工具负责“让 agent 自己确认具体代码真相”。

### 5.3 并发增量代码不可见时怎么处理

从代码 RAG 切到 Unix 探索以后，一个需要显式说明的问题是：

并发 task 尚未 merge 的 worktree 代码，默认不会像旧设计那样以 code snippets 形式互相可见。

这是刻意设计，不是缺陷，因为：

1. 未 merge 的 sibling worktree 还不属于全局真相。
2. 如果把多个并发 task 的半成品代码同时喂给模型，容易污染当前 task 判断。
3. coding agent 更适合拿到“并发风险摘要”，而不是直接吃到别的任务原始代码。

因此新的策略应固定为：

1. coding agent 默认只读：
   - 当前仓库基线
   - 自己当前 task workspace
   - docs / schema / config 等允许读取的稳定材料
2. 对 sibling task，不默认注入原始代码片段，而是补结构化摘要：
   - taskId
   - status
   - writeScopes
   - 最新 merge / delivered 状态
   - 是否与当前 task 存在潜在路径重叠
3. 如果因为其他并发 task 已经推进，导致当前 workspace base 过旧，应通过 drift 检测或 merge gate 暴露，而不是偷偷扩大读写边界。

也就是说：

1. 并发风险靠结构化摘要预警。
2. 真正的代码冲突靠 merge gate 兜底。
3. 模块级集成失败和语义冲突继续由模块集成测试闸门 + verify 发现。
4. 最终都回 architect 决定重规划、串行化还是补修复任务。

## 6. 新的工具模型

### 6.1 工具分层

建议把 coding 工具拆成三组：

1. 只读探索工具
2. 写入工具
3. 受控命令工具

### 6.2 只读探索工具

这组工具应足够强，尽量覆盖 Claude Code 常见的探索动作：

1. 列目录
2. 读文件
3. 模式搜索
4. 文件名/路径匹配
5. 查看头尾片段
6. 查看指定行范围
7. git 状态与 diff 摘要

推荐语义可以是：

1. `list_directory`
2. `read_file`
3. `grep_text`
4. `glob_files`
5. `read_range`
6. `git_status`
7. `git_diff_stat`
8. `git_head`

### 6.3 写入工具

写入仍然尽量保持显式和收口：

1. `write_file`
2. `edit_file`
3. `delete_file`

但无论用哪种语义，都必须继续受 `writeScopes` 硬限制。

### 6.4 受控命令工具

当前 `tool-shell.run_command(commandId)` 过于僵硬，不适合代码探索。

建议改成两层：

1. `exploration commands`
   - 专门允许只读命令模板
   - 支持参数化，例如 pattern、path、glob、line range
2. `execution commands`
   - 继续受 allowlist 控制
   - 用于测试、build、format、commit 等有副作用或高成本动作

## 7. 权限隔离设计

### 7.1 读权限

读权限默认策略建议改成：

1. 在当前 task workspace 内，允许广泛只读探索。
2. exploration roots 默认可覆盖整个仓库，或至少覆盖当前 task 可能关联的主目录。
3. docs / schema / config 可以读，不需要先通过 retrieval 预注入。
4. 如存在敏感路径，单独通过 deny list 排除，而不是把读权限整体收窄。

推荐心智模型：

1. `workspaceReadPolicy = BROAD`
2. `writePolicy = NARROW`

### 7.2 写权限

写权限继续严格限制在当前任务显式给出的 `writeScopes` 内。

不因为 agent 已经读到了某个文件，就自动获得对该文件的修改权。

### 7.3 超出写域时如何处理

这是本轮必须固定的主链规则：

1. 一旦 agent 判断实现需要修改写域外文件，不允许偷偷写。
2. 不允许自动扩大写域。
3. 不允许仅靠 shell 命令绕过写域。
4. 应立即结束当前 coding 回合，转成 blocker。
5. 如果同时需要新的资源授权或新的外部集成信息，也应通过同一 blocker 链路回到 architect，再由审批处理中心处理异步审批和结果回流。

blocker 的语义建议固定为两类：

1. `WRITE_SCOPE_ESCALATION_REQUIRED`
   - 当前任务方向正确，但现有写域不足
   - architect 可以选择扩权后继续
2. `TASK_REPLAN_REQUIRED`
   - 说明当前任务拆分本身不对
   - architect 需要重规划或拆新任务

### 7.4 写域重叠与工作区漂移治理

如果去掉代码 RAG，系统不能再指望 coding agent 通过看见别人未 merge 的代码来“自觉避冲突”。

因此要把冲突治理前移：

1. architect 规划时尽量拆开 writeScopes。
2. dispatcher 派发前检查并发 task 的 writeScopes 是否明显重叠。
3. 对重叠但不可避免的任务，优先串行而不是强行并发。
4. 对已经启动的 task，若主线在相关路径上发生推进，应识别 `workspace drift`。
5. merge gate 继续作为最终文本级冲突兜底。
6. 模块集成测试闸门 + verify 继续作为语义级冲突兜底。

## 8. Coding 回合的推荐流程

新的 coding 回合建议改成：

1. 先读结构化上下文，明确任务目标、写域、推荐探索范围、环境类型，以及 sibling task 风险摘要。
2. 检查当前 workspace 是否存在 base drift 或并发路径重叠风险。
3. 用只读探索工具确认仓库结构与相关文件。
4. 用 grep/glob/read_range 等工具逐步缩小问题范围。
5. 只有在证据足够时才进入写入动作。
6. 写入后再用受控命令执行测试/验证。
7. 如果需要改写写域外内容，立即 ASK_BLOCKER，而不是继续试探。

## 9. 对 ContextCompilationCenter 的影响

`ContextCompilationCenter` 仍然保留，但 coding pack 的职责会变化：

1. requirement / architect / verify
   - 继续允许按各自需要保留 retrieval 或证据摘要
2. coding
   - 默认不再带 repo code retrieval
   - 改为带更完整的环境事实、权限事实、探索范围和工具清单

## 10. 对 runtime/tooling 的影响

实现阶段至少会涉及这些边界调整：

1. `TaskExecutionContract`
   - 增加环境类型、探索范围、读权限策略等事实
2. `ContextCompilationCenter`
   - coding pack 不再默认带代码 snippets
3. `ToolRegistry / ToolExecutor`
   - 增加 Unix 风格只读探索原语
4. `CodingConversationAgent`
   - prompt 从“优先使用平台 retrieval + 有限工具”切到“先读结构化边界，再主动探索”
5. blocker 升级链路
   - 新增写域升级 / 重规划的标准 blocker 类型
6. dispatcher / merge-gate / integration-test-gate / verify
   - 增加 write scope overlap、workspace drift、sibling task summary 的治理与证据回流
7. repo graph lite
   - 作为 exploration roots 推荐和仓库范围提示的辅助视图，而不是 coding 主路径真相源

## 11. 当前结论

新的方向不是“把 RAG 做得更重”，而是：

1. 保留结构化事实层。
2. 把代码探索主路径切回 Unix 原语。
3. 读权限放宽到足以高效探索。
4. 写权限继续严格受任务边界约束。
5. 越界修改不自动放行，而是显式 blocker + architect 再决策。

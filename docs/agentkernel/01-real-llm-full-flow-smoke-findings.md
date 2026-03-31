# 真实 LLM 全链路 Smoke Findings

本文记录 `2026-03-29` 这次真实 DeepSeek 学生管理系统全链路 smoke 的真实情况，目标不是粉饰结果，而是把：

1. 哪些节点已经能直接吃真实输出
2. 哪些节点还需要 manual fallback
3. 当前 runtime / agent 基础设施还剩哪些收口点

明确写下来，方便后续继续升级。

## 1. 本次真实测试怎么跑

- 场景：学生管理系统最小后端版本
- 流程：`requirement -> architect -> task-graph -> worker-manager -> coding -> merge-gate -> verify`
- 真实模型：`deepseek-chat`
- 执行方式：
  - requirement / architect / coding / verify 先真实调用 DeepSeek
  - 若某节点真实输出不满足当前平台协议或模板约束，则记录原始输出并切到 manual fallback
  - workflow 继续沿真实 Docker + Git + Testcontainers MySQL runtime 跑完

本次运行命令：

```powershell
$env:AGENTX_LLM_SMOKE='true'
$env:AGENTX_DEEPSEEK_API_KEY='***'
.\mvnw.cmd -q -Dtest=DeepSeekRealWorkflowRuntimeIT test
```

本次运行结果：

- workflow：`COMPLETED`
- requirement：真实输出被接受
- architect：真实输出未被接受，改用 manual fallback
- coding：真实输出未被接受，改用 manual fallback
- verify：真实输出被接受

## 2. 当前产物位置

本次最新运行目录：

- `D:\DeskTop\agentx-platform\target\real-llm-smoke\student-management-runtime-1774799138165`

关键信息：

- 评估与 fallback 记录：
  - `D:\DeskTop\agentx-platform\target\real-llm-smoke\student-management-runtime-1774799138165\artifacts\evaluation-plan.json`
- workflow 最终结果：
  - `D:\DeskTop\agentx-platform\target\real-llm-smoke\student-management-runtime-1774799138165\artifacts\workflow-result.json`
- 各节点真实原始输出：
  - `requirement-first-real.json`
  - `requirement-second-real.json`
  - `architect-real.json`
  - `coding-implementation-real.json`
  - `coding-tests-real.json`
  - `verify-real.json`
- 方便人工审阅的代码 bundle：
  - `D:\DeskTop\agentx-platform\target\real-llm-smoke\student-management-runtime-1774799138165\review-bundle`

说明：

1. `review-bundle` 是本次给人工审阅用的代码快照。
2. 它来自两个 task 的导出快照拼装：
   - 实现任务提供 `src/main/java`
   - 测试任务提供 `src/test/java`
3. 之所以要拼装，是因为当前 runtime 仍以“task 级 merge candidate / cleanup”作为主要真相，不会保留一个长期存在的 workflow 最终整合分支。

## 3. 节点结论

### requirement

- 首轮真实输出：`NEED_INPUT`
- 二轮真实输出：`DRAFT_READY`
- 结论：可直接接受

优点：

1. 首轮能正确识别“做一个学生管理系统”信息不足。
2. 二轮在补充字段、校验、测试要求后，能形成一份结构完整的需求文档。

当前不足：

1. 首轮问题仍偏泛，需要继续学习平台常见需求模板，减少“泛平台咨询式”追问。
2. 二轮文档仍是自然语言整理，还没有更稳定地贴近平台里后续 planning 所需的边界格式。

### architect

- 真实输出：`PLAN_READY`
- 结论：结构能出来，但未直接接受

本次未接受原因：

1. `taskTemplateId` 输出成了平台不支持的自由值，例如 `IMPLEMENT_MODULE`、`CREATE_REGRESSION_TESTS`
2. `capabilityPackId` 输出成了平台外部语义，例如 `java-backend`、`java-testing`
3. `writeScopes` 也偏向“具体文件路径”，而不是当前平台要求的保守写域

说明：

1. 这说明 architect 的“任务规划思维”已经有了，但“平台模板语义对齐”还不够。
2. 当前问题不在于不会规划，而在于没学会严格受 `TaskTemplateCatalog + capability pack` 约束。

### coding

- 真实输出：`TOOL_CALL`
- 结论：有工具调用意识，但未直接接受

本次未接受原因：

1. coding 首轮选择了 `tool-filesystem.list_directory`
2. 但是路径写成了容器语义绝对路径 `/workspace`
3. 当前 runtime 的 filesystem tool 协议要求使用 workspace 内的相对路径

说明：

1. 这说明 coding agent 已经知道“先看目录结构”是合理动作。
2. 当前核心问题不是不会用工具，而是“平台工具协议细节对齐”还不够。
3. 这一层最适合通过 prompt、few-shot 和协议说明继续提升。

### verify

- 真实输出：`PASS`
- 结论：可直接接受

优点：

1. 能根据 deterministic verify 已通过这一事实，给出合理 `PASS`
2. 输出干净，没有乱加超范围升级或返工

## 4. 本次 manual fallback 实际做了什么

为了把 workflow 真正推进到底，本次 manual fallback 只接管了 architect 和 coding。

### architect fallback

人工固定成两个平台内合法任务：

1. `实现学生管理后端骨架`
2. `补齐学生管理回归测试`

并且显式使用当前平台受支持的：

1. `java-backend-code`
2. `java-backend-test`
3. `cap-java-backend-coding`

### coding fallback

人工按标准 `ToolCall` 协议推进：

1. 写入 `Student.java`
2. 写入 `StudentService.java`
3. 写入 `StudentController.java`
4. 写入 `StudentServiceTest.java`
5. 写入 runtime marker
6. `DELIVER`

重要的是：

1. fallback 并没有绕过 runtime
2. 仍然走的是当前正式 `ToolCall` 协议
3. 仍然经过真实 Docker / Git / merge-gate / verify

## 5. 代码质量快速结论

本次导出的 `review-bundle` 已额外执行：

```powershell
.\mvnw.cmd -q -f D:\DeskTop\agentx-platform\target\real-llm-smoke\student-management-runtime-1774799138165\review-bundle\pom.xml test
```

结果：通过

当前代码质量结论：

1. 代码结构基本符合“最小学生管理后端骨架”的预期
2. `Student / StudentService / StudentController / StudentServiceTest` 的职责划分清楚
3. 测试覆盖了新增、更新、删除和邮箱校验这几个最小场景
4. 它还不是一个完整可部署服务，但已经不是纯占位 marker，而是能跑最小单测的 Java 骨架

## 6. 下一步最该优化什么

高优先级：

1. architect prompt 对齐平台模板词表，强约束 `taskTemplateId / capabilityPackId / writeScopes`
2. coding prompt 明确强调 filesystem 工具只允许相对路径，不允许 `/workspace`
3. 给 coding 增加 2-3 组真实 tool-call few-shot，专门覆盖 `list_directory / read_file / write_file / DELIVER`

中优先级：

1. 给 architect 增加 catalog 注入后的合法模板示例
2. 给 coding 增加“先探索，再写文件，再 deliver”的 turn-level 示例
3. 给 requirement 增加“固定工作流常见需求补洞模板”，减少泛化追问

runtime 侧仍值得后续收口的点：

1. 保留 workflow 级最终整合代码分支或最终审阅快照，避免只能靠 task snapshot 拼 review bundle
2. 把当前 smoke harness 里这些“是否接受真实输出”的校验逐步沉淀成标准 eval rubric

## 7. 当前最诚实的结论

当前平台已经可以做到：

1. 真实调用 DeepSeek
2. 让 requirement 和 verify 在这个场景里直接产出可接受结构
3. 在 architect / coding 输出不够贴平台协议时，用同协议 manual fallback 把真实 runtime 主链跑通
4. 导出一份可编译、可测试、可人工审阅的学生管理代码 bundle

但还不能说：

1. architect 已经稳定学会平台模板语义
2. coding 已经稳定学会当前 tool 协议细节
3. 模型自己就能独立把整个学生管理流程一路推到底

这两件事必须继续留在下一轮 agent 能力升级里做，而不是假装它们已经解决。

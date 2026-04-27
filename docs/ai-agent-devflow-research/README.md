# AI 自动化软件开发流程调研索引

调研日期：2026-04-27

本目录沉淀了 Multica、AgentX、Claude Managed Agents 三条线的调查结果，以及一份横向方案比较和优点提取文档。

## 文档列表

1. [Multica 项目调查文档](./01-multica-investigation.md)
   - 重点：issue -> task -> daemon -> provider agent -> comment/status 的流转。
   - 适合学习：团队协作控制面、本地 runtime、agent as teammate、skills。

2. [AgentX 项目调查文档](./02-agentx-investigation.md)
   - 重点：session -> requirement -> architect ticket -> Task DAG -> context snapshot -> run -> Merge Gate 的流转。
   - 适合学习：PRD 到交付的长流程状态机、上下文治理、交付证据链。

3. [Claude Managed Agents 调查文档](./03-claude-managed-agents-investigation.md)
   - 重点：Agent / Environment / Session / Events / Tools / Vaults / Sandbox 的托管模型。
   - 适合学习：长任务 agent 基础设施、event log、harness/sandbox 解耦、安全边界。

4. [方案比较与优点提取](./04-comparison-and-design-extraction.md)
   - 重点：三者的定位、优缺点、可组合架构、推荐数据模型和学习路线。
   - 适合学习：如何综合三者优点设计自己的 AI 自动化软件开发平台。

5. [PRD / Issue 双单位生产流程设计草案](./05-prd-issue-production-flow-design.md)
   - 重点：把 Multica 的 issue 交互、AgentX 的 PRD 到交付流程、Managed Agents 的 session/event/runtime 抽象合成一套生产流程。
   - 适合学习：如何设计“轻量 issue 模式 + 复杂 PRD 模式”的双层状态流转。

## 快速结论

- Multica 最适合学习“如何把 coding agent 接入团队工作流”。
- AgentX 最适合学习“如何把 PRD 到交付流程化、状态机化、证据化”。
- Claude Managed Agents 最适合学习“如何设计长任务 agent 的托管基础设施”。
- 新的生产流程可以采用“双单位”设计：Issue 做日常执行单位，PRD 做复杂需求单位。

一个理想综合方案应该：

- 产品层像 Multica：workspace、issue、agent、comment、runtime、skills。
- 流程层像 AgentX：requirement、ticket、Task DAG、context snapshot、run、Merge Gate。
- 基础设施层像 Claude Managed Agents：versioned agent、environment、session event log、tools、vaults、permission、sandbox。

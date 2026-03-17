# 模块 04：总工-Worker 交互模块（Worker 池 + 工具包池 + Worker 创建）

更新时间：2026-02-23

范围说明：
本模块定义“架构师派活”阶段的最小机制，重点只覆盖：
1. Worker 的定义与创建（线程池/Worker 池模型）
2. 工具包池（Toolpacks/能力包）的定义与绑定方式
3. 任务如何标注“最小工具包集合”，并据此触发 Worker 创建

非目标（明确排除，避免跑偏）：
1. 不讨论 Worker 并发执行策略（不做调度算法、负载均衡、抢占等）
2. 不讨论 Worker 如何实际写代码/改文件/跑测试（那是后续执行模块）
3. 不讨论 QA/合并/交付流水线（后续模块）

---

## 1. 可行性与合理性评估（针对你提出的设计）

结论（先说结论）：
你的“Worker 池 + 工具包池 + 任务最小工具包集合”设计是可行的，并且在工程上是合理的。
它本质上是在做两件事：
1. 把“执行权限/环境能力”从 Agent 身上剥离出来，变成可配置、可审计的能力包（工具包）
2. 用“任务需求（required toolpacks）-> 可接任务的 Worker”这种硬约束，避免执行阶段随意漂移

合理性（为什么这条路对）：
1. 对齐你最核心的两条价值：人不写代码，但要能控住方向与风险
2. 符合你在复盘里强调的：边界优先于并发；工具包就是执行边界的一部分
3. 工业界有非常相近的成熟模式可以借鉴：
   - CI Runner 池：Jenkins agent / GitHub Actions runner / GitLab runner
   - K8s 调度 + 自动扩容：Pod 有需求约束，节点有能力标签，不满足就 Pending，满足就调度；无可用节点就扩容

主要风险（需要提前写进规则里，避免未来不可控）：
1. 工具包爆炸：版本/组合太多导致维护困难
2. 任务标注过宽：总工为了“省事”给任务标注一堆工具包，Worker 权限无限扩大
3. 能力与事实不一致：工具包声明“有 Java21”，但 Worker 实际环境不是同一版本，导致不可复现

对应的最小治理（本模块先定规则，不写实现）：
1. 工具包尽量原子化（一个工具/一个语言运行时一个版本），避免大礼包
2. 任务必须标注“最小集合”，并把“额外工具包”视为风险（必要时触发提请，让用户/架构师确认）
3. Worker 的能力来源必须可复现（例如固定镜像/固定安装脚本版本），否则工具包没有意义

---

## 2. 角色与职责（总工是否需要单独角色？）

### 2.1 总工（Foreman）的职责（建议以“职责”存在）

总工做的事不是写代码，而是把“架构产物”翻译成“可执行任务列表”，并确保每个任务有清晰的能力边界。

总工负责：
1. 任务拆分：把项目拆成模块与子任务（Work Items）
2. 能力标注：为每个任务标注完成它的“最小工具包集合”
3. Worker 供给：当任务因为缺少能力而无法被任何 Worker 接单时，触发 Worker 创建（受 Worker 池上限约束）

### 2.2 是否需要单独的“总工 Agent”（权衡）

建议（v0）：
先不新增 Agent，把“总工”当成架构师 Agent 的一个工作模式/责任集合（架构师兼任）。

理由（降低复杂度与上下文成本）：
1. 新增角色会增加一次移交与对齐成本（架构师 -> 总工），早期最容易在这里丢上下文
2. 你当前的重点是机制定型，不是把角色拆得很细

保留升级路径：
当任务拆分规模变大、架构师上下文明显过载时，再把“总工 Agent”拆出来，但它仍然不写代码，只做任务与能力管理。

---

## 3. 核心概念（白话版）

### 3.1 Worker 是什么

Worker 可以理解为“一个受限的命令行执行者”，它能做什么由它绑定的工具包决定。
你不需要把 Worker 当成人形 Agent，而是当成“可创建/可回收的执行槽位”。

本模块只关心 Worker 的创建与可接单能力，不关心它如何并发执行。

### 3.2 工具包（Toolpack）是什么

工具包是一组能力声明，用来回答两件事：
1. Worker 允许执行哪些命令/具备哪些运行时（权限与环境）
2. Worker 对某个技术栈版本的“事实约束”是什么（例如 Java 8 vs Java 21）

工具包和 skill 的区别（直白版）：
1. skill 解决“怎么做才对”（方法/流程/注意事项/踩坑规避）
2. 工具包解决“能不能做、用什么做”（命令权限/运行时/编译器/脚本工具等）

关键原因：
只靠 skill（提示与流程）无法保证环境事实一致（例如 Java 21 是否真的存在、Maven 是否可用、gcc 是否装了）。
因此在执行类系统里，通常需要单独的“能力池/工具包池”做硬约束。

### 3.3 任务的“最小工具包集合”

每个任务必须声明一个 required_toolpacks 集合。
只有当 Worker 的工具包集合覆盖 required_toolpacks（集合包含关系）时，该 Worker 才“有资格接这个任务”。

### 3.4 模块（Module）是什么（为什么要引入）

模块是总工拆分任务时的一级分组单位，用于：
1. 把任务组织成“可交付的边界”（后续做模块级验证/接口脚本测试会用到）
2. 让任务、测试、产物能挂在同一个模块下面，便于追溯“这个模块现在什么状态”

在机制上：任务属于模块；模块可以拥有自己的“测试计划/脚本入口”（后续模块再展开具体测试机制）。

### 3.5 能力池如何构建（是否需要虚拟环境？）

结论：
需要一个方便管理且可复现的“虚拟环境/隔离环境”，否则工具包只是一堆标签，无法保证事实一致与可审计。

工具包池的最小构建方式（MVP 思路）：
1. 工具包的“真实定义”放在版本化的清单文件里（而不是只放数据库表）
2. Worker 创建时按清单去准备环境，并用自检命令验证版本

当前实现状态（2026-02-23）：
1. 运行环境模式已做成可配置（`local | docker`），默认是 `docker`。
2. 运行环境准备入口是 `RuntimeEnvironmentPort.ensureReady(...)`，在自动补员流程里执行。
3. 目录分层：
   - `global-toolpacks/`：全局工具包就绪标记
   - `projects/<session_id>/<toolpack_fingerprint>/`：项目级环境
   - `environment.json`：记录运行模式、工具包与镜像/venv 信息
4. `docker` 模式（默认）：
   - 不要求宿主机安装 Java/Maven/Python/Git，对应能力通过容器镜像校验与准备；
   - 宿主机只要求 Docker 可用（daemon 可连通）；
   - 支持镜像拉取策略（`always | if-not-present | never`）。
5. `local` 模式（回退）：
   - 保留宿主机自检与 Python venv 创建能力（适合内网快速调试）。
6. 已增加环境清理机制：定时按 TTL 清理过期项目环境目录，避免长期堆积。

生产化演进建议（下一阶段）：
1. v0.2（当前）：默认 Docker 模式 + 可回退 local 模式。
2. v1：Worker runtime 执行阶段也切到容器工作流（run 级容器 + worktree 挂载 + 只读/可写范围约束）。
3. v2（可选）：Kubernetes 化（多节点弹性与更强治理）。

工具包清单（Toolpack Spec）里建议包含的最小信息（不等于数据库字段）：
1. `id/name/version/kind`
2. `provision`：如何获得该能力（容器镜像引用或安装脚本引用）
3. `verify`：如何自检（例如 `java -version`、`mvn -v`）
4. `policy`：允许的命令范围（至少要有 allowlist 思路，后续再细化）

### 3.6 多工具包组合时，能不能靠“管理提示词”解决？

结论：
提示词只能帮助“做法更对”，不能保证“事实一致”，更不能当作权限与兼容性约束。
组合问题需要分两类看：

1. 组合的是“命令能力/运行时能力”
   - 例如：`java21` + `maven` + `python` + `gcc`
   - 做法：用 required_toolpacks 做集合约束；Worker 按工具包准备环境并自检
   - 提示词可用来提醒注意事项，但不能代替“环境事实与权限控制”
2. 组合的是“框架/依赖版本差异带来的工程做法差异”
   - 例如：不同 Spring Boot 版本下 MyBatis 的推荐配置方式可能不同
   - 做法：这类差异更像“知识与工程实践”，应该由 skill/规则文档承担，并由 Stack Profile 选择对应版本的实践说明
   - 工具包可以负责“用哪个 Java/Maven”，skill 负责“怎么写才兼容这个版本”

### 3.7 能力池 vs Skill 池（为什么要能力池，而不是只靠 skill）

这不是“二选一”，而是分工：
1. 能力池（工具包池）负责硬约束：环境事实、执行权限、可复现
2. skill 池负责软约束：工程做法、提示模板、版本差异的最佳实践

为什么不能只做 skill 池（只用提示词管理一切）：
1. 权限不可控：skill 无法阻止 Worker 跑不该跑的命令
2. 环境不可复现：skill 无法保证机器上真的有 Java21、也无法保证版本一致
3. 难审计：出问题时无法用“能力声明 + 自检结果”定位到底是环境问题还是实现问题

为什么不能只做能力池（只用工具包，不要 skill）：
1. 质量不稳定：工具包只能保证“能运行”，不能保证“写得对/选得对”
2. 版本差异不可解释：框架用法差别需要可读的工程规则与注意事项
3. 决策不显式：很多工程取舍需要提请（方案对比），不是装个工具就能解决

优劣总结（与你的价值目标对齐）：
1. 能力池的优势：可控、可审计、可复现、可用于“任务能否接单”的硬判定
2. 能力池的代价：维护成本、组合爆炸风险、准备时间（需要 warm pool 缓解）
3. skill 的优势：表达复杂经验更高效，能覆盖框架版本差异与工程习惯
4. skill 的代价：属于软约束，若缺少能力池兜底，系统容易再次漂移

### 3.8 可组合 Skill 池（组合对/组合片段）怎么设计（避免预填太多）

你提的“Java21+MyBatis”“Maven+Java21”这种其实是典型的“组合片段（slice）”。
目标不是把所有组合都预填成完整 skill，而是：
1. 把常见组合沉淀成可复用的片段
2. 任务发生时按需拼接成一个“任务专用 skill”，并记录来源与版本，保证可追溯

这里给一个适合动态拼接的结构（MVP 可落地且不会爆炸）：

1. `Skill Fragment`（技能片段，原子化）
   - 定位：一小段可复用的工程做法说明，解决一个明确问题
   - 例子：
     - `java21-runtime`：Java21 的运行/编码注意事项 + 常见坑
     - `maven-build`：Maven 常见命令、常见失败定位路径
     - `springboot-3.2-mybatis`：在 Spring Boot 3.2 下使用 MyBatis 的典型配置方式与约束（不是代码，而是原则与检查点）
2. `Combo Pair/Slice`（组合对/切片，声明“经常一起出现”）
   - 定位：把常见组合显式化，避免每次都从零选片段
   - 例子：
     - `combo: java21 + maven`
     - `combo: springboot-3.2 + mybatis`
3. `Skill Bundle`（技能包，组合后的“配方”）
   - 定位：由多个 fragment + combo 组合而成，用于某一类任务（比如“实现一个 Spring MVC API + MyBatis 持久层 + mvn test”）
4. `Task Skill`（任务专用 skill，运行时产物）
   - 定位：针对某个具体任务，把 bundle 实例化成一个清晰的、无重复的执行指南（仍然不授权超出工具包的命令）

一个关键点：
fragment/bundle 解决“怎么做”，toolpack 解决“能不能做”。拼出来的 Task Skill 必须能映射到 required_toolpacks。

#### 3.8.1 Skill Fragment 的建议结构（便于拼接）

建议每个 fragment 用 Markdown 文件存储，并带一个非常轻的元信息头（你后续要工程化时好解析）：

```yaml
fragment:
  id: "springboot-3.2-mybatis"
  version: "1.0.0"
  applies_to:
    stack:
      springboot: "3.2.x"
      mybatis: ">=3.5"
  requires_toolpacks:
    - "java21"
    - "maven"
  intent_tags:
    - "persistence"
    - "config"
```

正文只写三类内容（避免变成大杂烩）：
1. 适用边界：适用于什么版本/什么场景，不适用什么
2. 做法要点：工程上应该遵循的原则与检查点
3. 验证建议：怎么确认“做对了”（比如应该跑哪些命令，应该看到什么现象）

#### 3.8.2 动态拼接流程（两段式，符合你提的“再调用一次大模型整合”）

阶段 A：确定性拼接（可审计、可复现）
1. 输入：任务的职能描述（intent）、Stack Profile、required_toolpacks、模块类型（web/persistence/test 等）
2. 规则选片段：
   - 优先按 combo slice 选（减少搜索空间）
   - 再补齐基础 fragment（语言/构建/测试）
3. 输出：`raw_task_skill.md`（只是按顺序拼接，允许重复）
4. 记录：拼接用到的 fragment id + version 列表（审计用）

阶段 B：整合修订（LLM 可参与，但要严格约束）
1. 输入：raw_task_skill + 任务 intent（“这个 Worker 这次要干什么”）
2. LLM 只允许做：
   - 去重、重排、统一术语
   - 把冲突点标成 open questions（不允许脑补）
   - 如果发现片段不足，列出“缺什么 fragment”，而不是自己新增知识
3. 输出：`task_skill.md`（用户/系统可读的最终版本）

可选：拆分优化（防止 fragment 越来越大）
如果整合后经常出现“某几段总是一起出现”，就反向生成一个新的 combo slice 或 bundle，但仍然保留来源引用。

#### 3.8.3 你提到的“用组合对动态形成完整 skill”是否成立？

成立，但需要加两条硬规则避免失控：
1. 组合对只能作为“选片段的捷径”，不能绕过 required_toolpacks（避免 skill 让 Worker 运行它没权限的命令）
2. 组合后的 task skill 必须携带“来源清单”（fragment list），否则不可追溯就会回到提示词漂移

---

## 4. 机制设计（只到 Worker 创建为止）

### 4.1 Worker 池配置（v0 最小参数 + 工程化增量参数）

v0 最小参数（只为支持 Worker 创建闭环）：
1. `max_workers_total`：Worker 池最大 Worker 数（硬上限）
2. `max_workers_active`：同时工作的 Worker 上限（本模块不使用，留到并发模块）

工程化建议的增量参数（不是必须，但会显著提升可管理性）：
1. `max_workers_provisioning`：同时处于 `PROVISIONING` 的 Worker 上限（防止创建风暴）
2. `provisioning_timeout_sec`：创建/准备 Worker 的超时时间（超时进入失败处理，避免永远卡住）
3. `min_ready_workers`：预备 Worker 数（预热池，降低高频任务的冷启动延迟）
4. `warm_toolpack_sets`：预备 Worker 对应的工具包组合（例如常用的 `java21+maven`）
5. `worker_max_age_sec`：Worker 最大存活时间（到期后滚动替换，避免环境长期漂移）
6. `worker_idle_ttl_sec`：Worker 空闲回收时间（控制成本）

### 4.2 Worker 创建触发逻辑（最小版）

对每个任务 `task`：
1. 总工创建任务并标注 `required_toolpacks`
2. 系统检查当前 Worker 池中是否存在任意 Worker 满足：
   - `required_toolpacks ⊆ worker_toolpacks`
3. 若存在：该任务“可接单”，不需要新建 Worker
4. 若不存在：该任务进入“等待 Worker 能力”的状态，并触发创建 Worker 的尝试

创建尝试规则：
1. 若 `workers_total < max_workers_total`
   - 创建一个新的 Worker
   - 为该 Worker 绑定 `required_toolpacks`（只绑定最小集合）
   - Worker 进入 `PROVISIONING`，完成后变为 `READY`
2. 若 `workers_total >= max_workers_total`
   - 不创建 Worker
   - 任务保持等待状态（pending）
   - 可选：若等待超过阈值，向总工/架构师发出“容量提请”（是否提升上限或调整任务工具包）

说明：
你提到的“如果某个任务超过某一时间没有人能接就反馈给总工创建新 Worker”，在机制上对应：
任务处于“等待 Worker 能力”的状态超过阈值后，总工收到提醒并触发创建尝试。
如果系统可以自动做创建尝试，也可以把“反馈”简化为自动事件记录，不需要人介入。

### 4.3 预备 Worker（Warm Pool）是否需要？

需要，而且是非常常见、很工程化的一步。

原因：
1. 你担心的“每次创建 Worker 都从 0 构建”会造成明显延迟，尤其当需求高频且工具包准备耗时（例如下载依赖）
2. 预备 Worker 不改变你的控制模型：它只是提前把常用能力准备好，仍然受 required_toolpacks 约束

最小做法：
1. 维持 `min_ready_workers` 个 `READY` Worker 处于空闲状态（未分配任务）
2. 这些 Worker 的工具包集合来自 `warm_toolpack_sets`
3. 当任务出现时优先复用预备 Worker；若工具包不匹配再走创建逻辑

风险与控制：
1. 预备 Worker 的能力必须随工具包版本滚动更新（否则“READY”不等于“可复现”）
2. 预备 Worker 数量需要和成本绑定（可作为容量提请的对象）

---

## 5. 最小状态与触发（避免无意义膨胀）

### 5.1 Worker 状态（只保留创建相关）

1. `PROVISIONING`：Worker 已创建但尚不可用（环境/工具包安装中）
2. `READY`：Worker 可用（具备声明的工具包能力）
3. `DISABLED`：Worker 被禁用/回收（本模块不讨论回收策略，只保留状态）

触发：
1. 创建 Worker -> `PROVISIONING`
2. 完成能力准备 -> `READY`
3. 人工或策略禁用 -> `DISABLED`

### 5.2 任务状态（只保留“能否被接单/是否需要创建 Worker”相关）

1. `PLANNED`：总工已创建任务并完成工具包标注
2. `WAITING_DEPENDENCY`：任务依赖未满足（上游任务未达到 required_upstream_status，v0 默认 `DONE`）
3. `WAITING_WORKER`：依赖已满足，但当前 Worker 池中不存在满足 required_toolpacks 的 Worker
4. `READY_FOR_ASSIGN`：依赖满足且存在满足 required_toolpacks 的 Worker（本模块不定义如何选哪一个）

注意：
1. 任务被并发分配后会进入 `ASSIGNED`，交付后会进入 `DELIVERED`，最终满足 DoD 后会进入 `DONE`，见 `docs/06-git-worktree-workflow.md`
2. 本模块只关注“到可接单为止”的状态与触发

触发：
1. 新建任务 -> `PLANNED`，随后立即检查可接单性
2. 检查依赖未满足 -> `WAITING_DEPENDENCY`
3. 依赖满足但无满足 Worker -> `WAITING_WORKER`
4. 依赖满足且有满足 Worker -> `READY_FOR_ASSIGN`
5. 新 Worker 创建成功/某 Worker 增加工具包/上游任务 `DONE` -> 重新检查

状态膨胀控制规则：
1. 如果只是记录细节（例如“安装 Maven 中”），用事件/日志，不要新增状态
2. 新增状态必须能改变下一步由谁推进（系统/总工/用户）或是否允许继续推进

---

## 6. 最小数据结构构想（MVP，避免过度设计）

说明：
本模块只给出最小可落地的数据结构。后续需要统计/检索时，再做字段与表的增量扩展。

### 6.1 工具包池（toolpacks）

```sql
create table toolpacks (
  toolpack_id      varchar(64) primary key,
  name             varchar(128) not null,
  version          varchar(64) not null,
  kind             varchar(64) not null, -- language | build | compiler | script | misc
  description      varchar(512) null,
  created_at       timestamp not null
);
```

### 6.2 Worker 池（workers + worker_toolpacks）

```sql
create table workers (
  worker_id        varchar(64) primary key,
  status           varchar(32) not null, -- PROVISIONING | READY | DISABLED
  created_at       timestamp not null,
  updated_at       timestamp not null
);

create table worker_toolpacks (
  worker_id        varchar(64) not null,
  toolpack_id      varchar(64) not null,
  primary key (worker_id, toolpack_id)
);
```

### 6.3 任务（work_tasks）

模块（work_modules）：

```sql
create table work_modules (
  module_id        varchar(64) primary key,
  session_id       varchar(64) not null,
  name             varchar(128) not null,
  description      varchar(512) null,
  created_at       timestamp not null,
  updated_at       timestamp not null
);
```

```sql
create table work_tasks (
  task_id                varchar(64) primary key,
  module_id              varchar(64) not null,
  title                  varchar(256) not null,
  task_template_id       varchar(64) not null,  -- tmpl.init.v0 | tmpl.impl.v0 | tmpl.verify.v0 | tmpl.bugfix.v0 | tmpl.refactor.v0 | tmpl.test.v0（见模块 05）
  status                 varchar(32) not null, -- PLANNED | WAITING_DEPENDENCY | WAITING_WORKER | READY_FOR_ASSIGN | ASSIGNED | DELIVERED | DONE
  required_toolpacks_json text not null,       -- JSON array of toolpack_id
  active_run_id           varchar(64) null,     -- 模块 06：并发领任务时写入当前 active run_id（本模块不展开）
  created_by_role         varchar(32) not null, -- architect_agent (as foreman)
  created_at              timestamp not null,
  updated_at              timestamp not null
);

create table work_task_dependencies (
  task_id                  varchar(64) not null,
  depends_on_task_id       varchar(64) not null,
  required_upstream_status varchar(32) not null, -- v0: DONE
  created_at               timestamp not null,
  primary key (task_id, depends_on_task_id)
);
```

说明（为什么用 JSON 而不是关联表）：
当前阶段更关注机制定型与最小闭环，JSON 可以减少表数量与迁移复杂度。
如果未来需要高效查询“某工具包影响哪些任务”，再把 required_toolpacks 拆为关联表即可。

---

## 7. 总工-Worker 交互场景（只到 Worker 创建）

### F1：总工拆分任务并标注 required_toolpacks

参与方：架构师(兼任总工)  
触发：架构师完成架构产物，需要进入任务拆分  
数据对象：
1. `work_modules`：创建模块（可选但推荐）
2. `work_tasks`：插入任务（归属某个模块），写入 `required_toolpacks_json`
结果：
1. 任务创建成功，进入可接单性检查（F2）

### F2：系统检查是否存在可接单 Worker

参与方：系统（或总工触发的检查动作）  
触发：新任务创建/任务更新 required_toolpacks/Worker 能力发生变化  
判定：
1. 若存在未满足依赖 -> 任务置为 `WAITING_DEPENDENCY`
2. 依赖满足且存在 `READY` Worker 覆盖 required_toolpacks -> 任务置为 `READY_FOR_ASSIGN`
3. 依赖满足但不存在 `READY` Worker -> 任务置为 `WAITING_WORKER`，进入 F3

### F3：触发 Worker 创建尝试（受 Worker 池上限约束）

参与方：系统（自动）或 总工（收到反馈后手动触发）  
触发：任务处于 `WAITING_WORKER` 且需要新能力  
规则：
1. 若 `workers_total < max_workers_total`：创建 Worker（F4）
2. 若 `workers_total >= max_workers_total`：保持等待（pending）

### F4：创建 Worker 并绑定工具包（最小集合）

参与方：系统  
触发：允许创建 Worker  
数据对象：
1. `workers`：插入一条记录，status=`PROVISIONING`
2. `worker_toolpacks`：绑定 required_toolpacks
结果：
1. Worker 准备完成后变为 `READY`
2. 重新触发 F2，使等待任务变为 `READY_FOR_ASSIGN`

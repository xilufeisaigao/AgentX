# 模块 03：项目设计模块（需求文档 + 工单 + 提请）

更新时间：2026-02-19

范围说明：
本模块把“需求文档结构”“工单模式结构”“提请与用户决策处理”统一成一个可追溯、可落地的设计模块，用于支撑：
1. 需求 Agent 只维护价值层约束（不碰架构细节）
2. 架构师 Agent 通过提请让用户做架构/实现方式决策（不写代码）
3. Agent 之间可以随时移交（不依赖真正的长连接）

术语引用：
如无特殊说明，本模块沿用 `docs/02-concepts-and-roles.md` 的概念定义（价值层约束、架构层变化、移交、提请、会话连接等）。

非目标：
1. 本模块不定义最终的“需求契约全文格式”（只给最小可落地结构草案，后续可版本化迭代）
2. 本模块不涉及任何具体代码实现
3. 本模块不展开后续执行/QA/合并等阶段（只覆盖“项目设计模块”内的需求与架构协作）

---

## 1. 设计原则（约束优先）

1. 单一事实来源：所有 Agent 协作都围绕同一个项目账本（项目会话 + 文档版本 + 工单事件链）
2. 强制可追溯：任何跨角色协作必须落到工单与事件；任何用户决策必须可回放
3. 不写代码但能控：把“需要取舍的点”结构化为提请，用户只做选择题/确认题
4. 价值与架构分离：需求文档只承载价值层约束；架构产物与 ADR 承载架构与实现方式取舍

---

## 2. 关键结构草案（先给你看）

本节只做“结构/表结构构想”，便于你先判断是否符合你的控制模型。

### 2.1 需求文档结构（价值层约束为主）

定位：
需求文档是“价值基线”，用于回答：做什么/为什么做/做到什么算成功/不能做什么/允许的风险是什么。
它必须可版本化、可确认、可引用，但不承载架构细节。

从 2026-02-21 起，需求文档正文格式固定为 `REQ-DOC-v1`，后端会做强校验。
标准与模板见：`docs/11-requirement-doc-standard.md`。

关键约束（冻结）：

1. 必须以 front matter 开头并包含 `schema_version: req_doc_v1`
2. 必须包含 10 个章节（Summary/Goals/Non-Goals/Scope/Acceptance Criteria/Value Constraints/Risks & Tradeoffs/Open Questions/References/Change Log）
3. 必须包含至少一条带标签条目：`[G-*] [NG-*] [AC-*] [VC-*] [R-*] [Q-*][OPEN|CLOSED]`
4. 文档正文是 markdown（仍存储于 `requirement_doc_versions.content`），但 shape 被标准固定

状态与触发（避免状态膨胀，最小集合）：
1. `DRAFT`：需求 Agent 创建了文档，但尚未进入用户审阅闭环
2. `IN_REVIEW`：用户正在审阅/编辑/提出意见，需求 Agent 需要继续迭代
3. `CONFIRMED`：用户明确确认“该版本需求文档符合需求”，形成价值基线

版本与确认规则（关键）：
1. `current_version`：最新版本号（每次修订 +1）
2. `confirmed_version`：最后一次被用户明确确认的版本号
3. 当 `CONFIRMED` 的需求文档发生价值层增量修改时：
   - 需求 Agent 先产生新版本并进入 `IN_REVIEW`
   - 只有用户再次确认后，才更新 `confirmed_version`

关键约束（保证职责边界）：
1. 需求 Agent 只修改上述“价值层字段”
2. 当用户提出“怎么实现/怎么拆模块/怎么建表”等架构层问题时，需求 Agent 必须创建工单移交架构师 Agent
3. 需求文档允许引用架构决策（decision/ADR），但不在文档内写实现细节

### 2.2 工单结构（Work Ticket）

定位：
工单是“协作与状态管理单元”，不是“通信连接”。它承载：派单、接单、等待、回写、审计。

最小工单字段（草案 v0）：

```yaml
ticket:
  ticket_id: "TCK-..."
  session_id: "SES-..."
  type: "HANDOFF | ARCH_REVIEW | DECISION | CLARIFICATION"
  title: "一句话描述"
  status: "OPEN | IN_PROGRESS | WAITING_USER | DONE | BLOCKED"

  created_by_role: "requirement_agent | architect_agent | user"
  assignee_role: "requirement_agent | architect_agent"

  related:
    requirement_doc_id: "REQ-..."
    requirement_doc_version: 3

  payload:
    kind: "handoff_packet | decision_request | clarification"
    data: {}  # 结构见后续 2.3/2.4

  lease:
    claimed_by: "agent_instance_id"
    lease_until: "timestamp"

  audit:
    created_at: "timestamp"
    updated_at: "timestamp"
```

工单类型与触发（最小集合）：
1. `HANDOFF`：需求 Agent 识别到“架构层问题/变化”或用户直接提出架构问题时触发，移交给架构师 Agent
2. `ARCH_REVIEW`：需求文档产生新的确认版本时触发（首次确认或后续增量确认），或由控制面在“任务编排/拆分已明显失效”时自动触发，要求架构师做影响评估并更新架构产物（即使最终结论是“无需改架构”也要有记录）
3. `DECISION`：任一 Agent 需要用户做明确选择/确认时触发（例如架构方案取舍、是否提交需求文档等）
4. `CLARIFICATION`：信息缺失导致无法继续推进时触发（需要用户补充事实/约束）

工单状态与触发（避免无意义膨胀，最小集合）：
1. `OPEN`：工单创建完成，等待被处理（尚未开始工作）
2. `IN_PROGRESS`：被指派的 Agent 正在处理（可以追加事件、产出草案、发起提请）
3. `WAITING_USER`：需要用户动作才能继续（选择方案/确认/补充信息）
4. `DONE`：工单目标达成，结果已回写到事件链与产物索引
5. `BLOCKED`：无法自动推进且需要人工处置（例如冲突无法收敛、输入长期缺失等）

状态膨胀控制规则（新增状态前先过这三条）：
1. 只有当“改变下一步由谁处理/是否允许系统继续推进”时，才新增状态
2. 若只是想记录过程细节或中间产物，优先追加 `ticket_events`，不要新增状态
3. 每个状态必须有清晰的进入条件与退出条件，并且必须明确由哪个角色推进状态

工单事件（审计链）：
每次状态变化、提请发出、用户响应、补充信息都追加一条事件，形成可回放链路。

```yaml
ticket_event:
  event_id: "EVT-..."
  ticket_id: "TCK-..."
  event_type: "STATUS_CHANGED | COMMENT | DECISION_REQUESTED | USER_RESPONDED | ARTIFACT_LINKED"
  actor: "user | requirement_agent | architect_agent"
  body: "人类可读摘要"
  data: {}          # 结构化信息（可选）
  created_at: "timestamp"
```

事件类型触发（仅列最常用的，避免泛化成“万能日志”）：
1. `STATUS_CHANGED`：工单状态发生变化时
2. `DECISION_REQUESTED`：发起提请（创建 `DECISION` 工单）时
3. `USER_RESPONDED`：用户对提请或澄清做出响应时
4. `ARTIFACT_LINKED`：产物（如 ADR/架构规格版本）已生成并与工单关联时

### 2.3 移交包结构（Handoff Packet）

定位：
移交包是“把问题一次性讲清楚”的结构，不靠聊天上下文运气。

最小结构（草案 v0）：

```yaml
handoff_packet:
  handoff_id: "HOF-..."
  from_role: "requirement_agent"
  to_role: "architect_agent"

  requirement_ref:
    doc_id: "REQ-..."
    version: 3

  requirement_delta:
    from_confirmed_version: 2
    to_version: 3
    summary: "本次价值层增量变更摘要（一句话）"

  user_change:
    summary: "用户刚刚提出的变化/问题"
    raw_user_text: "可选：用户原话"

  value_constraints:
    - "与本次问题相关的价值约束（从需求文档引用/摘录）"

  question_for_architect:
    - "需要架构师回答/输出的具体问题"
```

### 2.4 提请结构（Decision Request）

定位：
提请是“把架构/实现方式问题变成可选择的决策题”，让用户只做高价值决策。

最小结构（草案 v0）：

```yaml
decision_request:
  question: "需要用户决策的问题（明确且可选）"
  context:
    - "与需求/约束相关的背景（短、可核对）"

  options:
    - option_id: "OPT-A"
      title: "方案 A"
      pros:
        - "好处"
      cons:
        - "坏处"
      risks:
        - "风险"
      cost_notes:
        - "成本/复杂度说明"
    - option_id: "OPT-B"
      title: "方案 B"
      pros: []
      cons: []
      risks: []
      cost_notes: []

  recommendation:
    option_id: "OPT-A"
    reason: "为什么推荐（可审查）"

  user_action:
    allowed:
      - "choose_option"
      - "force_specify"   # 用户强制指定一个实现偏好（记录风险提示已告知）
      - "ask_more_info"   # 要求补充信息/更多方案
```

用户响应（Decision Response）：

```yaml
decision_response:
  user_choice:
    kind: "choose_option | force_specify | ask_more_info"
    option_id: "OPT-A"     # choose/force 时填
    note: "用户补充说明（可选）"
  responded_at: "timestamp"
```

---

## 3. 工单表结构构想（关系型数据库草案）

说明：
你要求“工单表如何设计”，这里给一个关系型数据库的最小可落地草案（字段可裁剪）。核心思想是：
1. `tickets` 存当前状态（便于查询）
2. `ticket_events` 存不可变事件链（便于审计与回放）
3. 提请/决策默认不单独建表：先以 `DECISION` 类型工单的 `payload_json` + `ticket_events` 承载（后续确有需要再做表结构拆分）

### 3.1 会话与文档

```sql
-- 项目会话：把一次“项目交付”串起来
create table sessions (
  session_id        varchar(64) primary key,
  title             varchar(256) not null,
  status            varchar(32) not null, -- ACTIVE | PAUSED | COMPLETED
  created_at        timestamp not null,
  updated_at        timestamp not null
);

-- 需求文档（只存元数据）
create table requirement_docs (
  doc_id            varchar(64) primary key,
  session_id        varchar(64) not null,
  current_version   int not null,
  confirmed_version int null,
  status            varchar(32) not null, -- DRAFT | IN_REVIEW | CONFIRMED
  title             varchar(256) not null,
  created_at        timestamp not null,
  updated_at        timestamp not null
);

-- 需求文档版本（存正文；MVP 只存 markdown，避免引入多格式复杂度）
create table requirement_doc_versions (
  doc_id            varchar(64) not null,
  version           int not null,
  content           text not null,
  created_by_role   varchar(32) not null, -- user | requirement_agent
  created_at        timestamp not null,
  primary key (doc_id, version)
);
```

### 3.2 工单与事件

```sql
create table tickets (
  ticket_id             varchar(64) primary key,
  session_id            varchar(64) not null,
  type                  varchar(32) not null,  -- HANDOFF | ARCH_REVIEW | DECISION | CLARIFICATION
  status                varchar(32) not null,  -- OPEN | IN_PROGRESS | WAITING_USER | DONE | BLOCKED
  title                 varchar(256) not null,

  created_by_role       varchar(32) not null,  -- user | requirement_agent | architect_agent
  assignee_role         varchar(32) not null,  -- requirement_agent | architect_agent

  requirement_doc_id    varchar(64) null,
  requirement_doc_ver   int null,
  payload_json          text not null,

  claimed_by            varchar(128) null,     -- agent instance id
  lease_until           timestamp null,

  created_at            timestamp not null,
  updated_at            timestamp not null
);

create table ticket_events (
  event_id              varchar(64) primary key,
  ticket_id             varchar(64) not null,
  event_type            varchar(64) not null,
  actor_role            varchar(32) not null,
  body                  text not null,
  data_json             text null,
  created_at            timestamp not null
);
```

并发与可靠性要点（不写代码，只写机制）：
1. 通过 `claimed_by + lease_until` 做“接单租约”，避免两个 Agent 同时处理同一工单
2. 所有状态变化必须追加 `ticket_events`，避免“只改当前状态但丢审计”

### 3.3 提请与用户响应（MVP：不单独建表）

存储映射（足够支撑“提请发出-用户处理-审计回放”）：
1. `DECISION` 工单的 `payload_json`：存 `decision_request`
2. 用户选择写入 `ticket_events(event_type=USER_RESPONDED)` 的 `data_json`：存 `decision_response`
3. 工单 `status=WAITING_USER` 表示“该提请正在等待用户处理”（即 Decision Inbox）

延后项（避免过度设计）：
如果未来需要对“提请选项/响应”做大量统计与索引，再考虑把 `decision_request/options/response` 拆成独立表结构。

---

## 4. 交互场景清单（用户/需求 Agent/架构师 Agent）

说明：
你要求“把所有交互场景详细列出，并结合数据结构”。下面每个场景都标注：
1. 参与方
2. 触发条件
3. 关键数据对象（需求文档/工单/提请）
4. 结果（状态如何推进、写入哪些产物）

### S1：需求收集与澄清（未提交）

参与方：用户 <-> 需求 Agent  
触发：用户开始描述需求，但信息不完整或仍在探索  
数据对象：
1. `sessions`：创建/选中 `session_id`
2. `requirement_doc_versions`：可选（需求 Agent 可先不落文档，只在准备成文时落 v1）
结果：
1. 需求 Agent 持续收集价值层约束与验收标准
2. 不产生提请；不移交架构师

### S2：需求 Agent 认为“已可开始初步开发”，询问是否提交

参与方：用户 <-> 需求 Agent  
触发：需求 Agent 判断价值层约束足够形成第一版需求文档  
数据对象：
1. `ticket`（可选）：创建 `CLARIFICATION` 或 `DECISION` 类型工单，表示“是否提交需求文档”
结果：
1. 用户若拒绝：回到 S1，继续澄清
2. 用户若同意：进入 S3

实现建议（v0 控制面）：
1. 允许通过自然语言触发词进入“提交尝试”（例如：`确认需求`）
2. 触发时若事实仍不足，需求 Agent 必须返回“信息不足 + 缺失项清单”，不得生成文档
3. 仅当价值层事实达到可提交阈值后，触发词才生效进入 S3
4. S1/S2 的澄清对话历史需按 `session_id` 持久化（如 Redis），S3 生成首版文档时必须拼接历史上下文，不得只使用最后一条触发语句。
5. Redis 历史仅用于 S1/S2 discovery，不作为长期事实源；进入文档修订（S4/S6）后，记忆基线应切换为 `requirement_doc_versions` 当前版本。

### S3：输出第一版需求文档（提交后）

参与方：需求 Agent -> 用户  
触发：用户同意提交并开始  
数据对象：
1. `requirement_docs`：创建 `doc_id`
2. `requirement_docs.status`：置为 `IN_REVIEW`（进入用户审阅闭环）
3. `requirement_doc_versions`：写入 version=1（正文内容）
结果：
1. 用户得到“完整需求文档（v1）”
2. 进入 S4 文档迭代
3. 前端应在固定区域显示 markdown 编辑器（Direct Edit 区），用户可直接编辑后提交新版本

### S4：用户编辑/提出意见，需求 Agent 修订需求文档

参与方：用户 <-> 需求 Agent  
触发：用户对需求文档提出修改（价值层）  
数据对象：
1. `requirement_doc_versions`：每次修订生成新版本（v2/v3/...）
2. `ticket_events`：可选，记录每次“用户反馈 -> 修订”
3. 记忆来源：以当前 `requirement_doc_versions` 最新正文为准（必要时结合 confirmed_version 与相关工单事件），不依赖 Redis 全量聊天历史
结果：
1. 文档版本递增
2. 直到用户确认，进入 S5

### S5：用户确认需求文档（形成价值基线）

参与方：用户 -> 需求 Agent  
触发：用户明确确认“需求文档符合我的需求”  
数据对象：
1. `requirement_docs.status` 变为 CONFIRMED
2. `requirement_docs.confirmed_version`：置为 `current_version`
结果：
1. 形成价值基线（Requirement Baseline）
2. 后续任何修改默认仍由需求 Agent 接收，但要按 S6/S7 分流
3. 触发 S6b：创建 `ARCH_REVIEW` 工单，让架构师产出/同步架构与数据模型产物

### S6：确认后的修改仍是价值层约束（需求 Agent 直接处理）

参与方：用户 <-> 需求 Agent  
触发：用户修改目标/范围/验收/风险接受等（不涉及架构实现方式）  
数据对象：
1. `requirement_docs.status`：进入 `IN_REVIEW`（开始对“增量版本”进行审阅闭环）
2. `requirement_doc_versions`：产生新版本 v(n+1)（必要时多轮迭代）
3. 用户确认后：`requirement_docs.status` 回到 `CONFIRMED`，并更新 `requirement_docs.confirmed_version`
结果：
1. 价值层增量需求形成新的确认版本（新的价值基线）
2. 触发 S6b：需求 Agent 创建 `ARCH_REVIEW` 工单移交架构师（用于增量架构同步）

### S6b：需求确认后触发“架构评估/同步”（ARCH_REVIEW）

参与方：需求 Agent -> 架构师 Agent（必要时再 -> 用户）  
触发：需求文档产生新的确认版本（首次确认或后续增量确认，`confirmed_version` 发生变化）  
数据对象：
1. `tickets`：创建 `ARCH_REVIEW` 工单，assignee=architect_agent，payload=handoff_packet（含 requirement_delta）
2. `ticket_events`：记录“已触发架构增量评估”
结果（分支）：
1. 若架构不受影响：架构师追加事件说明“无需架构变更”，并将工单置为 `DONE`
2. 若需要更新架构产物但无需用户决策：架构师更新架构规格/数据模型等产物索引，并将工单置为 `DONE`
3. 若存在取舍点需要用户拍板：架构师创建 `DECISION` 工单并置为 `WAITING_USER`，该 `ARCH_REVIEW` 工单同步进入 `WAITING_USER`，等待用户处理后再收口

### S7：确认后的修改涉及架构层变化（必须移交架构师）

参与方：用户 -> 需求 Agent -> 架构师 Agent -> 用户  
触发：用户提出“怎么拆模块/怎么存数据/如何保证一致性/是否异步”等架构层问题/变化  
数据对象：
1. `tickets`：创建 `HANDOFF` 工单，assignee=architect_agent，payload=handoff_packet
2. `ticket_events`：记录“已移交”
结果：
1. 架构师 Agent 接单，进入 S8

### S8：架构师 Agent 基于移交包发起提请（多方案优劣）

参与方：架构师 Agent -> 用户（通过提请）  
触发：收到 S7 的 HANDOFF 工单；或架构师发现存在关键取舍点  
数据对象：
1. `tickets`：创建 `DECISION` 工单，status=WAITING_USER，payload=decision_request
2. `ticket_events`：记录“提请已发出”
结果：
1. 用户进入 S9 处理提请

### S9：用户处理提请（选择/强制/要求更多信息）

参与方：用户 -> 架构师 Agent（或通过统一 Decision Inbox）  
触发：用户看到提请并响应  
数据对象：
1. `ticket_events`：记录“用户已响应”（`data_json` 写入 decision_response）
结果：
1. 若 `ask_more_info`：回到 S8（架构师补充方案/澄清再提请）
2. 若 `choose_option/force_specify`：进入 S10

### S10：架构师收口决策，回写架构产物，并与需求文档对齐

参与方：架构师 Agent <-> 需求 Agent  
触发：用户对提请做出明确选择  
数据对象：
1. `ticket_events`：记录“决策已收口/产物已回写”
2. 产物索引（本模块不规定存储形式）：写入 `adr_id`、逻辑架构规格版本号、数据模型规格版本号等
3. `requirement_doc_versions`：需求 Agent 生成新版本，只增加/更新 references（引用 decision/ADR），不写实现细节
结果：
1. 架构决策可追溯
2. 价值基线与架构选择保持一致

---

## 5. 用数据结构串起来的端到端运作流程（项目设计模块）

目标：
把“交互逻辑 + 数据流转 + 工单推进”连成一个可执行的系统流程。

### 5.1 启动会话

1. 创建 `sessions(session_id)`
2. 需求 Agent 开始与用户交互（S1）

### 5.2 需求文档形成与确认

1. 用户同意提交（S2）
2. 创建 `requirement_docs(doc_id)` 与 `requirement_doc_versions(doc_id, v1)`（S3）
3. 反复写入 `requirement_doc_versions`（S4）
4. 用户确认后标记 CONFIRMED（S5）

### 5.3 架构问题出现时的“随时移交”

1. 需求文档确认（首次或增量）后触发架构同步（S6b）
2. 创建 `tickets(type=ARCH_REVIEW, assignee=architect_agent, payload=handoff_packet)`
3. 若用户直接提出架构层问题/变化（S7）
   - 创建 `tickets(type=HANDOFF, assignee=architect_agent, payload=handoff_packet)`
4. 架构师处理上述工单并在需要时发起提请（S8）
   - 创建 `tickets(type=DECISION, status=WAITING_USER, payload=decision_request)`
   - 追加 `ticket_events` 记录“提请已发出”
5. 用户处理提请（S9）
   - 追加 `ticket_events(event_type=USER_RESPONDED)`，写入 decision_response
6. 架构师收口并产出 ADR/规格；需求 Agent 只做引用对齐（S10）

### 5.4 “一直连接”如何成立（不依赖长连接）

在该流程下，“一直连接”是由以下事实保证的：
1. 所有状态都在 `tickets/status` 与 `ticket_events` 中
2. 任意 Agent 随时可通过查询“指派给我且 OPEN/WAITING_USER/IN_PROGRESS 的工单”继续推进
3. 若要低延迟，可以在“工单创建/状态变更”时发通知（消息队列/WebSocket/长轮询等），但通知不是事实来源

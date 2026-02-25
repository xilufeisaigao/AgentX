# Requirement Document Standard (REQ-DOC-v1)

Last updated: 2026-02-21

## 1. Purpose

This document freezes the requirement markdown format used by the `requirement` module.
The goal is to make requirement content:

1. machine-checkable before version persistence
2. stable for LLM generation and revision
3. auditable across `requirement_doc_versions`

This standard applies to:

1. `POST /api/v0/requirement-docs/{docId}/versions`
2. `POST /api/v0/sessions/{sessionId}/requirement-agent/drafts` (LLM-generated drafts)

## 2. Mandatory Format

Each requirement markdown must include:

1. YAML front matter with `schema_version`:
   - English: `req_doc_v1`
   - Chinese: `req_doc_v1_zh`
2. the exact section set below
3. required tagged bullets for validation

```markdown
---
schema_version: req_doc_v1
---

# <Title>

## 1. Summary
<one short summary paragraph>

## 2. Goals
- [G-1] ...

## 3. Non-Goals
- [NG-1] ...

## 4. Scope
### In
- [S-IN-1] ...
### Out
- [S-OUT-1] ...

## 5. Acceptance Criteria
- [AC-1] ...

## 6. Value Constraints
- [VC-1] ...

## 7. Risks & Tradeoffs
- [R-1] ...

## 8. Open Questions
- [Q-1][OPEN] ...

## 9. References
### Decisions
- [DEC-...] ...
### ADRs
- [ADR-...] ...

## 10. Change Log
- v<version>: ...
```

Chinese-equivalent schema (`req_doc_v1_zh`) uses equal semantics with localized headings:

```markdown
---
schema_version: req_doc_v1_zh
---

# <标题>

## 1. 摘要
<简短摘要段落>

## 2. 目标
- [G-1] ...

## 3. 非目标
- [NG-1] ...

## 4. 范围
### 包含
- [S-IN-1] ...
### 不包含
- [S-OUT-1] ...

## 5. 验收标准
- [AC-1] ...

## 6. 价值约束
- [VC-1] ...

## 7. 风险与权衡
- [R-1] ...

## 8. 开放问题
- [Q-1][待确认] ...

## 9. 参考
### 决策
- [DEC-...] ...
### 架构决策记录
- [ADR-...] ...

## 10. 变更记录
- v<version>: ...
```

## 3. Validation Rules (Server-Side)

The backend rejects content when any rule fails:

1. front matter missing or not closed
2. `schema_version` missing or not `req_doc_v1`
3. missing mandatory sections:
   - for `req_doc_v1`: Summary / Goals / Non-Goals / Scope / Acceptance Criteria / Value Constraints / Risks & Tradeoffs / Open Questions / References / Change Log
   - for `req_doc_v1_zh`: 摘要 / 目标 / 非目标 / 范围 / 验收标准 / 价值约束 / 风险与权衡 / 开放问题 / 参考 / 变更记录
4. missing mandatory tagged items:
   - at least one `[G-#]`
   - at least one `[NG-#]`
   - at least one `[AC-#]`
   - at least one `[VC-#]`
   - at least one `[R-#]`
   - at least one `[Q-#][OPEN|CLOSED|待确认|已关闭]`

## 4. Boundary Reminder

REQ-DOC-v1 is value-layer only.
Do not put architecture implementation details into this document.
Architecture decisions must flow through `HANDOFF` / `ARCH_REVIEW` / `DECISION` tickets.

## 5. Frontend Editing Slot (Reserved)

After backend returns a draft (`phase=DRAFT_CREATED` or `phase=DRAFT_REVISED`),
frontend should render a fixed **Direct Edit** markdown area for the current content.

Submission contract:
1. User direct edit submits via `POST /api/v0/requirement-docs/{docId}/versions`
2. `content` still must satisfy REQ-DOC-v1 validation
3. Backend remains source of truth for versioning (`current_version` / `confirmed_version`)

import { readPreferredLocale, translate, translateServerValue, translateTokenValue } from "./i18n";

export const API_TIMEOUT_MS = 30000;
export const AUTO_REFRESH_MS = 15000;
export const PHASE_STEPS = ["DRAFTING", "REVIEWING", "WAITING_USER", "EXECUTING", "DELIVERED", "COMPLETED"];
export const PROJECT_VIEWS = [
  { id: "overview", label: "Overview" },
  { id: "requirement", label: "Requirement" },
  { id: "tickets", label: "Inbox" },
  { id: "execution", label: "Execution" },
  { id: "delivery", label: "Delivery" },
];
export const OPS_VIEWS = [
  { id: "runtime", label: "Runtime" },
  { id: "workers", label: "Workers" },
];
export const EXECUTION_VIEWS = [
  { id: "tasks", label: "Tasks" },
  { id: "runs", label: "Runs" },
];

export async function apiRequest(path, options = {}) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), API_TIMEOUT_MS);

  try {
    const response = await fetch(path, {
      method: options.method || "GET",
      headers: options.body ? { "Content-Type": "application/json" } : undefined,
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    });

    if (options.allow404 && response.status === 404) {
      return { __notFound: true };
    }

    if (response.status === 204) {
      return null;
    }

    const text = await response.text();
    const data = text ? safeParseJson(text) ?? text : null;

    if (!response.ok) {
      const message = typeof data === "object" && data
        ? read(data, "message") || read(data, "error")
        : text;
      throw new Error(message || `${options.method || "GET"} ${path} failed with ${response.status}`);
    }

    return data;
  } finally {
    window.clearTimeout(timeout);
  }
}

export function read(source, name) {
  if (!source) {
    return null;
  }
  if (source[name] !== undefined && source[name] !== null) {
    return source[name];
  }
  const snake = name.replace(/[A-Z]/g, (part) => `_${part.toLowerCase()}`);
  return source[snake] !== undefined ? source[snake] : null;
}

export function arrayOf(source, name) {
  const value = read(source, name);
  return Array.isArray(value) ? value : [];
}

export function safeParseJson(value) {
  if (!value || typeof value !== "string") {
    return typeof value === "object" ? value : null;
  }
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

export function prettyJson(value) {
  const parsed = typeof value === "string" ? safeParseJson(value) : value;
  if (parsed) {
    return JSON.stringify(parsed, null, 2);
  }
  return String(value || "");
}

export function formatDateTime(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  const locale = readPreferredLocale() === "en" ? "en-US" : "zh-CN";
  return new Intl.DateTimeFormat(locale, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

export function statusTone(value) {
  const status = String(value || "").toUpperCase();
  if (["RUNNING", "ACTIVE", "READY", "SUCCEEDED", "CONFIRMED", "IN_PROGRESS"].includes(status)) {
    return "active";
  }
  if (["WAITING_USER", "WAITING_FOREMAN", "WAITING_WORKER", "WAITING_DEPENDENCY", "REVIEWING", "DRAFTING", "PAUSED", "DELIVERED"].includes(status)) {
    return "waiting";
  }
  if (["FAILED", "BLOCKED", "CANCELLED", "DISABLED", "MISSING"].includes(status)) {
    return "danger";
  }
  return "neutral";
}

export function statusLabel(value) {
  return String(value || "UNKNOWN")
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

export function getRequirementDoc(source) {
  return read(source, "currentRequirementDoc");
}

export function getSessionId(session) {
  return read(session, "sessionId") || "";
}

export function getSessionTitle(session) {
  return read(session, "title") || translate(readPreferredLocale(), "untitledSession");
}

export function getDocId(doc) {
  return read(doc, "docId") || "";
}

export function getTicketId(ticket) {
  return read(ticket, "ticketId") || "";
}

export function flattenTasks(board) {
  return arrayOf(board, "modules").flatMap((module) => arrayOf(module, "tasks"));
}

export function findTicketById(inbox, ticketId) {
  return arrayOf(inbox, "tickets").find((ticket) => getTicketId(ticket) === ticketId) || null;
}

export function findTaskById(board, taskId) {
  return flattenTasks(board).find((task) => read(task, "taskId") === taskId) || null;
}

export function findRunById(timeline, runId) {
  return arrayOf(timeline, "items").find((run) => read(run, "runId") === runId) || null;
}

export function countStatus(items, status) {
  return items.filter((item) => read(item, "status") === status).length;
}

export function inferRequestKind(ticket) {
  const payload = safeParseJson(read(ticket, "payloadJson"));
  return read(ticket, "requestKind") || payload?.request_kind || payload?.requestKind || null;
}

export function inferTicketQuestion(ticket) {
  const locale = readPreferredLocale();
  return translateServerValue(
    read(ticket, "question")
      || read(ticket, "latestEventBody")
      || read(ticket, "title")
      || (locale === "en"
        ? "More context is required before execution can continue."
        : "需要补充上下文后才能继续执行。"),
    locale,
  );
}

function deriveFallbackPhase(detail, tickets) {
  const status = read(detail, "status");
  const doc = getRequirementDoc(detail);
  if (status === "COMPLETED") {
    return "COMPLETED";
  }
  if (tickets.some((ticket) => read(ticket, "status") === "WAITING_USER")) {
    return "WAITING_USER";
  }
  if (!doc) {
    return "DRAFTING";
  }
  if (read(doc, "status") !== "CONFIRMED") {
    return "REVIEWING";
  }
  return "EXECUTING";
}

function deriveFallbackAction(doc, waitingUser) {
  const locale = readPreferredLocale();
  if (waitingUser > 0) {
    return translate(locale, "primaryWaitingDescription", { count: waitingUser });
  }
  if (!doc) {
    return locale === "en" ? "Draft the requirement first." : "先起草需求。";
  }
  if (read(doc, "status") !== "CONFIRMED") {
    return locale === "en" ? "Review and confirm the requirement." : "审阅并确认需求。";
  }
  return translate(locale, "primaryExecutionDescription");
}

export function buildFallbackProgress(detail, tickets) {
  const locale = readPreferredLocale();
  const doc = getRequirementDoc(detail);
  const waitingUser = tickets.filter((ticket) => read(ticket, "status") === "WAITING_USER").length;
  const openTickets = tickets.filter((ticket) => !["DONE", "BLOCKED"].includes(read(ticket, "status"))).length;
  const sessionStatus = read(detail, "status") || "ACTIVE";
  const phase = deriveFallbackPhase(detail, tickets);
  const blockers = [];

  if (!doc) {
    blockers.push(locale === "en" ? "The session does not have a requirement document yet." : "当前会话还没有需求文档。");
  } else if (read(doc, "status") !== "CONFIRMED") {
    blockers.push(locale === "en" ? "Requirement is not confirmed yet." : "需求尚未确认。");
  }
  if (waitingUser > 0) {
    blockers.push(locale === "en" ? `${waitingUser} WAITING_USER ticket(s) require a reply.` : `当前有 ${waitingUser} 个等待用户的工单。`);
  }
  if (phase === "EXECUTING") {
    blockers.push(locale === "en" ? "Execution read models are not fully available yet. Confirm tasks and runs in Execution." : "执行读模型尚未完全返回，任务和运行信息可能需要到执行页确认。");
  }

  return {
    sessionId: getSessionId(detail),
    title: getSessionTitle(detail),
    sessionStatus,
    phase,
    blockerSummary: blockers[0] || (locale === "en" ? "There is no explicit blocker right now." : "当前没有显式阻塞。"),
    primaryAction: deriveFallbackAction(doc, waitingUser),
    requirement: doc
      ? {
        docId: getDocId(doc),
        currentVersion: read(doc, "currentVersion") || 0,
        confirmedVersion: read(doc, "confirmedVersion"),
        status: read(doc, "status"),
        title: read(doc, "title"),
        updatedAt: read(doc, "updatedAt"),
      }
      : null,
    taskCounts: {
      total: 0,
      planned: 0,
      waitingDependency: 0,
      waitingWorker: 0,
      readyForAssign: 0,
      assigned: 0,
      delivered: 0,
      done: 0,
    },
    ticketCounts: {
      total: tickets.length,
      open: openTickets,
      inProgress: countStatus(tickets, "IN_PROGRESS"),
      waitingUser,
      done: countStatus(tickets, "DONE"),
      blocked: countStatus(tickets, "BLOCKED"),
    },
    runCounts: {
      total: 0,
      running: 0,
      waitingForeman: 0,
      succeeded: 0,
      failed: 0,
      cancelled: 0,
    },
    latestRun: null,
    delivery: {
      deliveryTagPresent: false,
      deliveredTaskCount: 0,
      doneTaskCount: 0,
      latestDeliveryTaskId: null,
      latestDeliveryCommit: null,
      latestVerifyRunId: null,
      latestVerifyStatus: null,
    },
    canCompleteSession: sessionStatus === "COMPLETED",
    completionBlockers: sessionStatus === "COMPLETED" ? [] : blockers,
    createdAt: read(detail, "createdAt"),
    updatedAt: read(detail, "updatedAt"),
    source: "fallback",
  };
}

export function buildTicketInboxFallback(sessionId, tickets) {
  return {
    sessionId,
    appliedStatusFilter: null,
    totalTickets: tickets.length,
    waitingUserTickets: tickets.filter((ticket) => read(ticket, "status") === "WAITING_USER").length,
    tickets: tickets.map((ticket) => ({
      ticketId: getTicketId(ticket),
      type: read(ticket, "type"),
      status: read(ticket, "status"),
      title: read(ticket, "title"),
      createdByRole: read(ticket, "createdByRole"),
      assigneeRole: read(ticket, "assigneeRole"),
      requirementDocId: read(ticket, "requirementDocId"),
      requirementDocVer: read(ticket, "requirementDocVer"),
      payloadJson: read(ticket, "payloadJson"),
      claimedBy: read(ticket, "claimedBy"),
      leaseUntil: read(ticket, "leaseUntil"),
      createdAt: read(ticket, "createdAt"),
      updatedAt: read(ticket, "updatedAt"),
      latestEventType: null,
      latestEventBody: null,
      latestEventDataJson: null,
      latestEventAt: null,
      sourceRunId: null,
      sourceTaskId: null,
      requestKind: inferRequestKind(ticket),
      question: inferTicketQuestion(ticket),
      needsUserAction: read(ticket, "status") === "WAITING_USER",
    })),
    source: "fallback",
  };
}

export function fallbackSessionSummary(requirement) {
  const locale = readPreferredLocale();
  if (!requirement) {
    return translate(locale, "draftRequirementMissing");
  }
  const noun = locale === "en" ? "Requirement" : "需求";
  return `${noun} ${translateTokenValue(read(requirement, "status") || "UNKNOWN", locale)} / v${read(requirement, "currentVersion") || 0}`;
}

export function buildRuntimeEditor(config) {
  return {
    outputLanguage: read(config, "outputLanguage") || "zh-CN",
    requirementLlm: buildRuntimeProfile(read(config, "requirementLlm")),
    workerRuntimeLlm: buildRuntimeProfile(read(config, "workerRuntimeLlm")),
  };
}

function buildRuntimeProfile(profile) {
  return {
    provider: read(profile, "provider") || "",
    framework: read(profile, "framework") || "",
    baseUrl: read(profile, "baseUrl") || "",
    model: read(profile, "model") || "",
    timeoutMs: String(read(profile, "timeoutMs") || 120000),
    apiKey: "",
    apiKeyMasked: read(profile, "apiKeyMasked") || "",
  };
}

export function buildRuntimeRequest(editor) {
  return {
    output_language: editor.outputLanguage.trim() || "zh-CN",
    requirement_llm: buildRuntimeRequestProfile(editor.requirementLlm),
    worker_runtime_llm: buildRuntimeRequestProfile(editor.workerRuntimeLlm),
  };
}

export function buildRequirementTemplate(title, locale = readPreferredLocale()) {
  const safeTitle = title || (locale === "en" ? "Untitled Requirement" : "未命名需求");
  if (locale === "en") {
    return `---
schema_version: req_doc_v1
---

# ${safeTitle}

## 1. Summary
Describe the goal and delivery scope here.

## 2. Goals
- [G-1]

## 3. Non-Goals
- [NG-1]

## 4. Scope
### In
- [S-IN-1]

### Out
- [S-OUT-1]

## 5. Acceptance Criteria
- [AC-1]

## 6. Value Constraints
- [VC-1]

## 7. Risks & Tradeoffs
- [R-1]

## 8. Open Questions
- [Q-1][OPEN]

## 9. References
### Decisions
- [DEC-1]

### ADRs
- [ADR-001]

## 10. Change Log
- v0.1 (${new Date().toISOString().slice(0, 10)}): Initial draft by user
`;
  }
  return `---
schema_version: req_doc_v1_zh
---

# ${safeTitle}

## 1. 摘要
请在这里写清楚本次需求的目标和交付范围。

## 2. 目标
- [G-1] 

## 3. 非目标
- [NG-1] 

## 4. 范围
### 包含
- 

### 不包含
- 

## 5. 验收标准
- [AC-1] 

## 6. 价值约束
- [VC-1] 

## 7. 风险与权衡
- [R-1] 

## 8. 开放问题
- [Q-1][待确认] 

## 9. 参考
### 决策
- 

### 架构决策记录
- ADR-001: 

## 10. 变更记录
| 版本 | 日期 | 作者 | 描述 |
|------|------|------|------|
| 0.1 | ${new Date().toISOString().slice(0, 10)} | User | 初始草案 |
`;
}

function buildRuntimeRequestProfile(profile) {
  const payload = {
    provider: profile.provider.trim(),
    framework: profile.framework.trim(),
    base_url: profile.baseUrl.trim(),
    model: profile.model.trim(),
    timeout_ms: Number(profile.timeoutMs || 120000),
  };
  if (profile.apiKey.trim()) {
    payload.api_key = profile.apiKey.trim();
  }
  return payload;
}

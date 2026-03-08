const API_TIMEOUT_MS = 30000;
const AUTO_REFRESH_MS = 15000;
const PHASE_STEPS = ["DRAFTING", "REVIEWING", "WAITING_USER", "EXECUTING", "DELIVERED", "COMPLETED"];
const PROJECT_TABS = [
  { id: "overview", label: "Overview" },
  { id: "requirement", label: "Requirement" },
  { id: "tickets", label: "Tickets" },
  { id: "tasks", label: "Tasks" },
  { id: "runs", label: "Runs" },
  { id: "delivery", label: "Delivery" },
];
const OPS_TABS = [
  { id: "runtime", label: "Runtime" },
  { id: "workforce", label: "Workforce" },
];

const state = {
  sessions: [],
  activeSessionId: null,
  activeWorkspace: "project",
  activeProjectTab: "overview",
  activeOpsTab: "runtime",
  sessionDetails: new Map(),
  progressBySession: new Map(),
  ticketInboxBySession: new Map(),
  taskBoardBySession: new Map(),
  runTimelineBySession: new Map(),
  cloneRepoBySession: new Map(),
  ticketEventsById: new Map(),
  selectedTicketIdBySession: new Map(),
  selectedTaskIdBySession: new Map(),
  selectedRunIdBySession: new Map(),
  requirementComposerBySession: new Map(),
  requirementContentBySession: new Map(),
  ticketReplyById: new Map(),
  runtimeConfig: null,
  runtimeEditor: null,
  runtimeProbe: null,
  workers: null,
  lastAutomationResult: null,
  detail: null,
  refreshTimer: null,
};

const refs = {
  workspaceSwitch: document.getElementById("workspaceSwitch"),
  systemLead: document.getElementById("systemLead"),
  systemSummary: document.getElementById("systemSummary"),
  sessionList: document.getElementById("sessionList"),
  missionEyebrow: document.getElementById("missionEyebrow"),
  activeSessionTitle: document.getElementById("activeSessionTitle"),
  activeSessionMeta: document.getElementById("activeSessionMeta"),
  headerActions: document.getElementById("headerActions"),
  phaseRibbon: document.getElementById("phaseRibbon"),
  tabStrip: document.getElementById("tabStrip"),
  mainView: document.getElementById("mainView"),
  detailDrawer: document.getElementById("detailDrawer"),
  detailTitle: document.getElementById("detailTitle"),
  detailContent: document.getElementById("detailContent"),
  toast: document.getElementById("toast"),
};

document.addEventListener("DOMContentLoaded", () => {
  document.addEventListener("click", handleClick);
  document.addEventListener("input", handleInput);
  void bootstrap();
});

async function handleClick(event) {
  const actionElement = event.target.closest("[data-action]");
  if (actionElement) {
    event.preventDefault();
    await withErrorToast(async () => {
      const action = actionElement.dataset.action;
      switch (action) {
        case "create-session":
          await createSession();
          break;
        case "refresh-session":
          await refreshActiveSession(true);
          break;
        case "pause-session":
        case "resume-session":
        case "complete-session":
          await runSessionCommand(action.replace("-session", ""));
          break;
        case "save-requirement":
          await saveRequirementContent();
          break;
        case "create-draft":
          await submitRequirementDraft(actionElement.dataset.persist !== "false");
          break;
        case "confirm-requirement":
          await confirmRequirement();
          break;
        case "reply-ticket":
          await submitTicketResponse(actionElement.dataset.ticketId || "");
          break;
        case "publish-clone":
          await publishCloneRepo();
          break;
        case "refresh-runtime":
          await loadRuntimeConfig(true);
          break;
        case "test-runtime":
          await testRuntimeConfig();
          break;
        case "apply-runtime":
          await applyRuntimeConfig();
          break;
        case "refresh-workers":
          await loadWorkers(true);
          break;
        case "auto-provision":
          await runAutomation("/api/v0/workforce/auto-provision", {}, "Worker 自动供给已执行。");
          break;
        case "auto-run":
          await runAutomation("/api/v0/workforce/runtime/auto-run", {}, "Worker 自动运行已执行。");
          break;
        case "lease-recovery":
          await runAutomation("/api/v0/execution/lease-recovery", {}, "Lease recovery 已执行。");
          break;
        case "cleanup-workers":
          await runAutomation("/api/v0/workforce/cleanup", {}, "Worker cleanup 已执行。");
          break;
        case "close-drawer":
          state.detail = null;
          render();
          break;
        default:
          break;
      }
    });
    return;
  }

  const workspaceElement = event.target.closest("[data-workspace]");
  if (workspaceElement) {
    event.preventDefault();
    await withErrorToast(() => switchWorkspace(workspaceElement.dataset.workspace || "project"));
    return;
  }

  const tabElement = event.target.closest("[data-tab]");
  if (tabElement) {
    event.preventDefault();
    await withErrorToast(() => switchTab(tabElement.dataset.tab || "overview"));
    return;
  }

  const detailElement = event.target.closest("[data-detail-kind]");
  if (detailElement) {
    event.preventDefault();
    await withErrorToast(() => openDetail(detailElement.dataset.detailKind || "", detailElement.dataset.detailId || ""));
    return;
  }

  const sessionElement = event.target.closest("[data-session-id]");
  if (sessionElement) {
    event.preventDefault();
    const sessionId = sessionElement.dataset.sessionId || "";
    if (sessionId && sessionId !== state.activeSessionId) {
      await withErrorToast(() => selectSession(sessionId));
    }
  }
}

function handleInput(event) {
  const model = event.target.dataset.model;
  if (!model) {
    return;
  }

  const sessionId = state.activeSessionId;
  switch (model) {
    case "requirement-title":
      getRequirementComposer(sessionId).title = event.target.value;
      break;
    case "requirement-user-input":
      getRequirementComposer(sessionId).userInput = event.target.value;
      break;
    case "requirement-content":
      state.requirementContentBySession.set(sessionId, event.target.value);
      break;
    case "ticket-reply":
      state.ticketReplyById.set(event.target.dataset.ticketId || "", event.target.value);
      break;
    default:
      updateRuntimeEditor(model, event.target.value, event.target.dataset.profile || "");
      break;
  }
}

async function bootstrap() {
  render();
  await Promise.allSettled([loadSessions(), loadRuntimeConfig(false), loadWorkers(false)]);
  if (!state.activeSessionId && state.sessions.length > 0) {
    state.activeSessionId = getSessionId(state.sessions[0]);
  }
  if (state.activeSessionId) {
    await refreshActiveSession(false);
  } else if (state.activeWorkspace === "ops") {
    await Promise.allSettled([loadRuntimeConfig(false), loadWorkers(false)]);
  }
  render();
  startAutoRefresh();
}

function startAutoRefresh() {
  if (state.refreshTimer) {
    window.clearInterval(state.refreshTimer);
  }
  state.refreshTimer = window.setInterval(() => {
    if (document.hidden) {
      return;
    }
    if (document.activeElement && ["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) {
      return;
    }
    void refreshActiveSession(false);
  }, AUTO_REFRESH_MS);
}

async function switchWorkspace(workspace) {
  state.activeWorkspace = workspace === "ops" ? "ops" : "project";
  state.detail = null;
  if (state.activeWorkspace === "ops") {
    await Promise.allSettled([loadRuntimeConfig(false), loadWorkers(false)]);
  } else {
    await loadCurrentTabData(state.activeSessionId);
  }
  render();
}

async function switchTab(tabId) {
  if (state.activeWorkspace === "ops") {
    state.activeOpsTab = tabId;
  } else {
    state.activeProjectTab = tabId;
  }
  state.detail = null;
  await loadCurrentTabData(state.activeSessionId);
  render();
}

async function selectSession(sessionId) {
  state.activeSessionId = sessionId;
  state.detail = null;
  await refreshActiveSession(false);
}

async function refreshActiveSession(showToastMessage) {
  if (state.activeWorkspace === "ops" && !state.activeSessionId) {
    await Promise.allSettled([loadRuntimeConfig(false), loadWorkers(false)]);
    render();
    return;
  }
  if (!state.activeSessionId) {
    render();
    return;
  }

  await loadSessionDetail(state.activeSessionId);
  await Promise.allSettled([loadProgress(state.activeSessionId), loadCurrentTabData(state.activeSessionId)]);
  render();

  if (showToastMessage) {
    showToast("工作台已刷新。");
  }
}

function currentTab() {
  return state.activeWorkspace === "ops" ? state.activeOpsTab : state.activeProjectTab;
}

async function loadCurrentTabData(sessionId) {
  if (state.activeWorkspace === "ops") {
    await Promise.allSettled([loadRuntimeConfig(false), loadWorkers(false)]);
    return;
  }

  if (!sessionId) {
    return;
  }

  switch (state.activeProjectTab) {
    case "tickets":
      await loadTicketInbox(sessionId);
      break;
    case "tasks":
      await loadTaskBoard(sessionId);
      break;
    case "runs":
      await loadRunTimeline(sessionId);
      break;
    case "delivery":
      await loadCloneRepoPublication(sessionId);
      break;
    default:
      break;
  }
}

async function createSession() {
  const defaultTitle = `mission-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-")}`;
  const title = window.prompt("输入新的 Session 标题", defaultTitle);
  if (!title || !title.trim()) {
    return;
  }

  const created = await apiRequest("/api/v0/sessions", {
    method: "POST",
    body: { title: title.trim() },
  });
  await loadSessions();
  await selectSession(getSessionId(created));
  showToast("Session 已创建。");
}

async function runSessionCommand(command) {
  if (!state.activeSessionId) {
    throw new Error("当前没有可操作的 Session。");
  }
  if (command === "complete" && !window.confirm("确认尝试完成当前 Session？")) {
    return;
  }
  await apiRequest(`/api/v0/sessions/${state.activeSessionId}/${command}`, { method: "POST" });
  await refreshActiveSession(false);
  showToast(`Session ${command} 已执行。`);
}

async function submitRequirementDraft(persist) {
  if (!state.activeSessionId) {
    throw new Error("当前没有活动 Session。");
  }
  const composer = getRequirementComposer(state.activeSessionId);
  const detail = state.sessionDetails.get(state.activeSessionId);
  const doc = getRequirementDoc(detail);
  const response = await apiRequest(`/api/v0/sessions/${state.activeSessionId}/requirement-agent/drafts`, {
    method: "POST",
    body: {
      title: composer.title.trim() || getSessionTitle(detail),
      user_input: composer.userInput.trim(),
      doc_id: doc ? getDocId(doc) : null,
      persist,
    },
  });

  composer.assistantMessage = read(response, "assistantMessage") || "";
  composer.readyToDraft = Boolean(read(response, "readyToDraft"));
  composer.missingInformation = arrayOf(response, "missingInformation");
  if (read(response, "content")) {
    state.requirementContentBySession.set(state.activeSessionId, read(response, "content"));
  }

  await loadSessionDetail(state.activeSessionId);
  await loadProgress(state.activeSessionId);
  render();
  showToast(persist ? "需求草稿已生成。" : "需求缺口分析已完成。");
}

async function saveRequirementContent() {
  if (!state.activeSessionId) {
    throw new Error("当前没有活动 Session。");
  }
  const content = (state.requirementContentBySession.get(state.activeSessionId) || "").trim();
  if (!content) {
    throw new Error("需求内容不能为空。");
  }

  const docId = await ensureRequirementDoc(state.activeSessionId);
  await apiRequest(`/api/v0/requirement-docs/${docId}/content`, {
    method: "PUT",
    body: { content },
  });
  await loadSessionDetail(state.activeSessionId);
  await loadProgress(state.activeSessionId);
  render();
  showToast("需求文档已保存为新版本。");
}

async function confirmRequirement() {
  if (!state.activeSessionId) {
    throw new Error("当前没有活动 Session。");
  }
  const detail = state.sessionDetails.get(state.activeSessionId);
  const doc = getRequirementDoc(detail);
  if (!doc) {
    throw new Error("当前 Session 还没有 requirement doc。");
  }

  await apiRequest(`/api/v0/requirement-docs/${getDocId(doc)}/confirm`, { method: "POST" });
  await Promise.allSettled([loadSessionDetail(state.activeSessionId), loadProgress(state.activeSessionId), loadTicketInbox(state.activeSessionId)]);
  render();
  showToast("Requirement 已确认。");
}

async function submitTicketResponse(ticketId) {
  if (!ticketId) {
    throw new Error("缺少 ticketId。");
  }
  const body = (state.ticketReplyById.get(ticketId) || "").trim();
  if (!body) {
    throw new Error("请输入响应内容。");
  }

  const ticket = findTicketById(state.activeSessionId, ticketId);
  const dataJson = JSON.stringify({
    source: "mission_room",
    request_kind: getRequestKind(ticket) || null,
  });

  await apiRequest(`/api/v0/tickets/${ticketId}/events`, {
    method: "POST",
    body: {
      event_type: "USER_RESPONDED",
      actor_role: "user",
      body,
      data_json: dataJson,
    },
  });

  state.ticketReplyById.set(ticketId, "");
  await Promise.allSettled([loadTicketInbox(state.activeSessionId), loadProgress(state.activeSessionId), loadTicketEvents(ticketId)]);
  render();
  showToast("Ticket 响应已提交。");
}

async function publishCloneRepo() {
  if (!state.activeSessionId) {
    throw new Error("当前没有活动 Session。");
  }
  const response = await apiRequest(`/api/v0/sessions/${state.activeSessionId}/delivery/clone-repo`, { method: "POST" });
  state.cloneRepoBySession.set(state.activeSessionId, response);
  render();
  showToast("交付 clone 地址已发布。");
}

async function testRuntimeConfig() {
  const probe = await apiRequest("/api/v0/runtime/llm-config:test", {
    method: "POST",
    body: buildRuntimeRequest(),
  });
  state.runtimeProbe = probe;
  render();
  showToast(read(probe, "allOk") ? "运行时配置探测通过。" : "运行时配置存在失败项。", !read(probe, "allOk"));
}

async function applyRuntimeConfig() {
  await apiRequest("/api/v0/runtime/llm-config:apply", {
    method: "POST",
    body: buildRuntimeRequest(),
  });
  await loadRuntimeConfig(true);
  render();
  showToast("运行时配置已应用。");
}

async function runAutomation(path, body, successMessage) {
  const result = await apiRequest(path, { method: "POST", body });
  state.lastAutomationResult = {
    action: path,
    payload: result,
    executedAt: new Date().toISOString(),
  };
  await Promise.allSettled([loadWorkers(false), loadProgress(state.activeSessionId), loadCurrentTabData(state.activeSessionId)]);
  render();
  showToast(successMessage);
}

async function loadSessions() {
  const data = await apiRequest("/api/v0/sessions");
  state.sessions = Array.isArray(data) ? data : [];
  if (!state.activeSessionId && state.sessions.length > 0) {
    state.activeSessionId = getSessionId(state.sessions[0]);
  }
  render();
}

async function loadSessionDetail(sessionId) {
  if (!sessionId) {
    return null;
  }
  const detail = await apiRequest(`/api/v0/sessions/${sessionId}`);
  state.sessionDetails.set(sessionId, detail);
  seedRequirementState(sessionId, detail);
  return detail;
}

async function loadProgress(sessionId) {
  if (!sessionId) {
    return null;
  }
  const response = await apiRequest(`/api/v0/sessions/${sessionId}/progress`, { allow404: true });
  if (response && !response.__notFound) {
    state.progressBySession.set(sessionId, response);
    return response;
  }

  const detail = state.sessionDetails.get(sessionId) || await loadSessionDetail(sessionId);
  const tickets = await listTicketsRaw(sessionId);
  const fallback = buildFallbackProgress(detail, tickets);
  state.progressBySession.set(sessionId, fallback);
  return fallback;
}

async function loadTicketInbox(sessionId) {
  if (!sessionId) {
    return null;
  }
  const response = await apiRequest(`/api/v0/sessions/${sessionId}/ticket-inbox`, { allow404: true });
  if (response && !response.__notFound) {
    state.ticketInboxBySession.set(sessionId, response);
    ensureSelectedTicket(sessionId, arrayOf(response, "tickets"));
    return response;
  }

  const tickets = await listTicketsRaw(sessionId);
  const fallback = {
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

  state.ticketInboxBySession.set(sessionId, fallback);
  ensureSelectedTicket(sessionId, fallback.tickets);
  return fallback;
}

async function loadTaskBoard(sessionId) {
  if (!sessionId) {
    return null;
  }
  const response = await apiRequest(`/api/v0/sessions/${sessionId}/task-board`, { allow404: true });
  if (response && !response.__notFound) {
    state.taskBoardBySession.set(sessionId, response);
    ensureSelectedTask(sessionId, flattenTasks(response));
    return response;
  }

  const fallback = {
    sessionId,
    unavailableReason: "当前容器还未加载 task-board read-model。重建 backend 后，这里会展示按模块分组的任务看板。",
  };
  state.taskBoardBySession.set(sessionId, fallback);
  return fallback;
}

async function loadRunTimeline(sessionId) {
  if (!sessionId) {
    return null;
  }
  const response = await apiRequest(`/api/v0/sessions/${sessionId}/run-timeline?limit=40`, { allow404: true });
  if (response && !response.__notFound) {
    state.runTimelineBySession.set(sessionId, response);
    ensureSelectedRun(sessionId, arrayOf(response, "items"));
    return response;
  }

  const fallback = {
    sessionId,
    unavailableReason: "当前容器还未加载 run-timeline read-model。重建 backend 后，这里会展示 run 事件时间线。",
  };
  state.runTimelineBySession.set(sessionId, fallback);
  return fallback;
}

async function loadTicketEvents(ticketId) {
  if (!ticketId) {
    return [];
  }
  const events = await apiRequest(`/api/v0/tickets/${ticketId}/events`);
  state.ticketEventsById.set(ticketId, Array.isArray(events) ? events : []);
  return events;
}

async function loadCloneRepoPublication(sessionId) {
  if (!sessionId) {
    return null;
  }
  const response = await apiRequest(`/api/v0/sessions/${sessionId}/delivery/clone-repo`, { allow404: true });
  if (response && !response.__notFound) {
    state.cloneRepoBySession.set(sessionId, response);
    return response;
  }
  state.cloneRepoBySession.delete(sessionId);
  return null;
}

async function loadRuntimeConfig(resetEditor) {
  const config = await apiRequest("/api/v0/runtime/llm-config");
  state.runtimeConfig = config;
  if (resetEditor || !state.runtimeEditor) {
    state.runtimeEditor = buildRuntimeEditor(config);
  }
  return config;
}

async function loadWorkers() {
  const workers = await apiRequest("/api/v0/workforce/workers?limit=256");
  state.workers = workers;
  return workers;
}

async function listTicketsRaw(sessionId) {
  const data = await apiRequest(`/api/v0/sessions/${sessionId}/tickets`);
  return Array.isArray(data) ? data : [];
}

async function ensureRequirementDoc(sessionId) {
  const detail = state.sessionDetails.get(sessionId) || await loadSessionDetail(sessionId);
  const currentDoc = getRequirementDoc(detail);
  if (currentDoc) {
    return getDocId(currentDoc);
  }

  const composer = getRequirementComposer(sessionId);
  const created = await apiRequest(`/api/v0/sessions/${sessionId}/requirement-docs`, {
    method: "POST",
    body: {
      title: composer.title.trim() || getSessionTitle(detail),
    },
  });
  await loadSessionDetail(sessionId);
  return getDocId(created);
}

function buildFallbackProgress(detail, tickets) {
  const doc = getRequirementDoc(detail);
  const waitingUser = tickets.filter((ticket) => read(ticket, "status") === "WAITING_USER").length;
  const openTickets = tickets.filter((ticket) => !["DONE", "BLOCKED"].includes(read(ticket, "status"))).length;
  const sessionStatus = read(detail, "status") || "ACTIVE";
  const phase = deriveFallbackPhase(detail, tickets);
  const blockers = [];

  if (!doc) {
    blockers.push("Session 还没有 requirement doc。");
  } else if (read(doc, "status") !== "CONFIRMED") {
    blockers.push("Requirement 尚未确认。");
  }
  if (waitingUser > 0) {
    blockers.push(`当前有 ${waitingUser} 个 WAITING_USER ticket。`);
  }
  if (phase === "EXECUTING") {
    blockers.push("当前容器未启用 progress read-model，无法读取完整 task/run 完成门禁。");
  }

  return {
    sessionId: getSessionId(detail),
    title: getSessionTitle(detail),
    sessionStatus,
    phase,
    blockerSummary: blockers[0] || "当前 session 没有显式阻塞，但完整运行态仍需 read-model 支撑。",
    primaryAction: deriveFallbackAction(doc, waitingUser),
    requirement: doc ? {
      docId: getDocId(doc),
      currentVersion: read(doc, "currentVersion") || 0,
      confirmedVersion: read(doc, "confirmedVersion"),
      status: read(doc, "status"),
      title: read(doc, "title"),
      updatedAt: read(doc, "updatedAt"),
    } : null,
    taskCounts: { total: 0, planned: 0, waitingDependency: 0, waitingWorker: 0, readyForAssign: 0, assigned: 0, delivered: 0, done: 0 },
    ticketCounts: { total: tickets.length, open: openTickets, inProgress: countStatus(tickets, "IN_PROGRESS"), waitingUser, done: countStatus(tickets, "DONE"), blocked: countStatus(tickets, "BLOCKED") },
    runCounts: { total: 0, running: 0, waitingForeman: 0, succeeded: 0, failed: 0, cancelled: 0 },
    latestRun: null,
    delivery: { deliveryTagPresent: false, deliveredTaskCount: 0, doneTaskCount: 0, latestDeliveryTaskId: null, latestDeliveryCommit: null, latestVerifyRunId: null, latestVerifyStatus: null },
    canCompleteSession: sessionStatus === "COMPLETED",
    completionBlockers: sessionStatus === "COMPLETED" ? [] : blockers,
    createdAt: read(detail, "createdAt"),
    updatedAt: read(detail, "updatedAt"),
    source: "fallback",
  };
}

function seedRequirementState(sessionId, detail) {
  const composer = getRequirementComposer(sessionId);
  const doc = getRequirementDoc(detail);
  if (!composer.title) {
    composer.title = doc ? read(doc, "title") : getSessionTitle(detail);
  }
  if (!state.requirementContentBySession.has(sessionId)) {
    state.requirementContentBySession.set(sessionId, doc ? read(doc, "content") || "" : "");
  }
}

function buildRuntimeEditor(config) {
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

function buildRuntimeRequest() {
  return {
    output_language: state.runtimeEditor.outputLanguage.trim() || "zh-CN",
    requirement_llm: buildRuntimeRequestProfile(state.runtimeEditor.requirementLlm),
    worker_runtime_llm: buildRuntimeRequestProfile(state.runtimeEditor.workerRuntimeLlm),
  };
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

function updateRuntimeEditor(model, value, profileKey) {
  if (!state.runtimeEditor) {
    return;
  }
  if (model === "output-language") {
    state.runtimeEditor.outputLanguage = value;
    return;
  }
  const profile = state.runtimeEditor[profileKey];
  if (!profile) {
    return;
  }
  const fieldMap = {
    provider: "provider",
    framework: "framework",
    "base-url": "baseUrl",
    "model-name": "model",
    "timeout-ms": "timeoutMs",
    "api-key": "apiKey",
  };
  const field = fieldMap[model];
  if (field) {
    profile[field] = value;
  }
}

function render() {
  renderWorkspaceSwitch();
  renderSystemStatus();
  renderSessionList();
  renderHeader();
  renderMainView();
  renderDetailDrawer();
}

function renderWorkspaceSwitch() {
  refs.workspaceSwitch.innerHTML = [
    renderWorkspaceButton("project", "Project"),
    renderWorkspaceButton("ops", "Ops"),
  ].join("");
}

function renderSystemStatus() {
  const progress = state.progressBySession.get(state.activeSessionId);
  const workers = state.workers;
  const lead = progress
    ? `当前焦点：${read(progress, "phase") || "UNKNOWN"} / ${read(progress, "blockerSummary") || "无显式阻塞"}`
    : "读取 Session 后会在这里显示当前阻塞与下一步动作。";

  refs.systemLead.textContent = lead;
  refs.systemSummary.innerHTML = [
    renderMetricChip("Sessions", state.sessions.length),
    renderMetricChip("Waiting User", read(read(progress, "ticketCounts"), "waitingUser") || 0),
    renderMetricChip("Running", read(read(progress, "runCounts"), "running") || 0),
    renderMetricChip("Ready Workers", workers ? read(workers, "readyWorkers") || 0 : 0),
  ].join("");
}

function renderSessionList() {
  if (state.sessions.length === 0) {
    refs.sessionList.innerHTML = renderEmptyState("没有 Session", "点击左上角“新建”开始一个新的工作流。");
    return;
  }

  refs.sessionList.innerHTML = state.sessions.map((session) => {
    const sessionId = getSessionId(session);
    const progress = state.progressBySession.get(sessionId);
    const requirement = getRequirementDoc(session);
    return `
      <button type="button" class="session-card ${sessionId === state.activeSessionId ? "is-active" : ""}" data-session-id="${escapeHtml(sessionId)}">
        <div class="session-card__top">
          <p class="session-card__title">${escapeHtml(getSessionTitle(session))}</p>
          ${renderStatusPill(read(session, "status"), "status")}
        </div>
        <p class="session-card__blocker">${escapeHtml(read(progress, "blockerSummary") || fallbackSessionSummary(requirement))}</p>
        <div class="session-card__metrics">
          <div class="session-card__metric">
            <strong>${escapeHtml(read(progress, "phase") || deriveFallbackPhase(session, []))}</strong>
            <span>Phase</span>
          </div>
          <div class="session-card__metric">
            <strong>${read(read(progress, "ticketCounts"), "waitingUser") || 0}</strong>
            <span>Waiting User</span>
          </div>
          <div class="session-card__metric">
            <strong>${read(read(progress, "runCounts"), "running") || 0}</strong>
            <span>Running</span>
          </div>
        </div>
      </button>
    `;
  }).join("");
}

function renderHeader() {
  const detail = state.activeSessionId ? state.sessionDetails.get(state.activeSessionId) : null;
  const progress = state.activeSessionId ? state.progressBySession.get(state.activeSessionId) : null;
  refs.missionEyebrow.textContent = state.activeWorkspace === "ops" ? "Ops Console" : "Project Workspace";
  refs.activeSessionTitle.textContent = detail ? getSessionTitle(detail) : (state.activeWorkspace === "ops" ? "运行时与 worker 运维" : "选择一个 Session");
  refs.activeSessionMeta.textContent = detail
    ? `${getSessionId(detail)} · ${read(detail, "status")} · 更新于 ${formatDateTime(read(detail, "updatedAt"))}`
    : "当前没有活动 Session。";

  refs.headerActions.innerHTML = renderHeaderActions(detail, progress);
  refs.phaseRibbon.innerHTML = state.activeWorkspace === "project" ? renderPhaseRibbon(progress) : "";
  refs.tabStrip.innerHTML = renderTabStrip();
}

function renderHeaderActions(detail, progress) {
  const buttons = [
    renderActionButton("刷新", "refresh-session", "ghost"),
  ];

  if (detail && state.activeWorkspace === "project") {
    const status = read(detail, "status");
    if (status === "ACTIVE") {
      buttons.push(renderActionButton("暂停", "pause-session", "ghost"));
    }
    if (status === "PAUSED") {
      buttons.push(renderActionButton("恢复", "resume-session", "primary"));
    }
    buttons.push(renderActionButton("完成 Session", "complete-session", "primary", !read(progress, "canCompleteSession")));
  }

  if (state.activeWorkspace === "project" && currentTab() === "delivery") {
    buttons.push(renderActionButton("发布 Clone 地址", "publish-clone", "ghost"));
  }

  if (state.activeWorkspace === "ops") {
    buttons.push(renderActionButton("刷新 Runtime", "refresh-runtime", "ghost"));
    buttons.push(renderActionButton("刷新 Workers", "refresh-workers", "ghost"));
  }

  return buttons.join("");
}

function renderPhaseRibbon(progress) {
  const current = read(progress, "phase") || "DRAFTING";
  const currentIndex = Math.max(PHASE_STEPS.indexOf(current), 0);
  return PHASE_STEPS.map((step, index) => {
    const classes = ["phase-step"];
    if (index < currentIndex) {
      classes.push("is-complete");
    }
    if (step === current) {
      classes.push("is-current");
    }
    return `<div class="${classes.join(" ")}">${escapeHtml(step.replace("_", " "))}</div>`;
  }).join("");
}

function renderTabStrip() {
  const tabs = state.activeWorkspace === "ops" ? OPS_TABS : PROJECT_TABS;
  return tabs.map((tab) => `
    <button type="button" class="tab-button ${currentTab() === tab.id ? "is-active" : ""}" data-tab="${tab.id}">
      ${escapeHtml(tab.label)}
    </button>
  `).join("");
}

function renderMainView() {
  if (state.activeWorkspace === "ops") {
    refs.mainView.innerHTML = currentTab() === "workforce" ? renderWorkforceView() : renderRuntimeView();
    return;
  }

  if (!state.activeSessionId) {
    refs.mainView.innerHTML = renderEmptyState("没有选中的 Session", "从左侧选择一个 Session，或创建新的工作流。");
    return;
  }

  switch (state.activeProjectTab) {
    case "requirement":
      refs.mainView.innerHTML = renderRequirementView();
      break;
    case "tickets":
      refs.mainView.innerHTML = renderTicketView();
      break;
    case "tasks":
      refs.mainView.innerHTML = renderTaskBoardView();
      break;
    case "runs":
      refs.mainView.innerHTML = renderRunTimelineView();
      break;
    case "delivery":
      refs.mainView.innerHTML = renderDeliveryView();
      break;
    default:
      refs.mainView.innerHTML = renderOverviewView();
      break;
  }
}

function renderOverviewView() {
  const progress = state.progressBySession.get(state.activeSessionId);
  if (!progress) {
    return renderEmptyState("正在读取 Progress", "后端返回 session 聚合视图后，这里会展示 blocker、任务、run 与交付摘要。");
  }

  const requirement = read(progress, "requirement");
  const taskCounts = read(progress, "taskCounts") || {};
  const ticketCounts = read(progress, "ticketCounts") || {};
  const runCounts = read(progress, "runCounts") || {};
  const latestRun = read(progress, "latestRun");

  return `
    <section class="overview-hero">
      <div>
        <p class="section-kicker">Current Blocker</p>
        <h3>${escapeHtml(read(progress, "blockerSummary") || "当前没有显式阻塞")}</h3>
        <p>${escapeHtml(read(progress, "primaryAction") || "继续查看会话进度。")}</p>
        ${read(progress, "source") === "fallback" ? `<p class="field-hint">当前在兼容模式：backend 还没有热更新到新的 progress read-model。</p>` : ""}
      </div>
      <div class="metric-strip">
        ${renderMetricTile(read(taskCounts, "total") || 0, "Tasks")}
        ${renderMetricTile(read(ticketCounts, "waitingUser") || 0, "Waiting User")}
        ${renderMetricTile(read(runCounts, "running") || 0, "Running")}
        ${renderMetricTile(read(progress, "canCompleteSession") ? "YES" : "NO", "Can Complete")}
      </div>
    </section>
    <section class="overview-grid">
      <article class="span-4">
        <p class="section-kicker">Requirement</p>
        <h3 class="section-title">${escapeHtml(requirement ? read(requirement, "title") : "尚未创建")}</h3>
        <div class="section-body">
          <div class="detail-line"><span>状态</span>${renderStatusPill(requirement ? read(requirement, "status") : "MISSING", "phase")}</div>
          <div class="detail-line"><span>当前版本</span><strong>${requirement ? read(requirement, "currentVersion") || 0 : 0}</strong></div>
          <div class="detail-line"><span>确认版本</span><strong>${requirement ? read(requirement, "confirmedVersion") || "-" : "-"}</strong></div>
        </div>
      </article>
      <article class="span-4">
        <p class="section-kicker">Task Status</p>
        <div class="stat-grid">
          ${renderStatTile(read(taskCounts, "waitingWorker") || 0, "Waiting Worker")}
          ${renderStatTile(read(taskCounts, "assigned") || 0, "Assigned")}
          ${renderStatTile(read(taskCounts, "delivered") || 0, "Delivered")}
          ${renderStatTile(read(taskCounts, "done") || 0, "Done")}
          ${renderStatTile(read(taskCounts, "readyForAssign") || 0, "Ready")}
          ${renderStatTile(read(taskCounts, "planned") || 0, "Planned")}
        </div>
      </article>
      <article class="span-4">
        <p class="section-kicker">Ticket & Run</p>
        <div class="stat-grid">
          ${renderStatTile(read(ticketCounts, "open") || 0, "Open Tickets")}
          ${renderStatTile(read(ticketCounts, "waitingUser") || 0, "Waiting User")}
          ${renderStatTile(read(runCounts, "waitingForeman") || 0, "Waiting Foreman")}
          ${renderStatTile(read(runCounts, "succeeded") || 0, "Succeeded")}
          ${renderStatTile(read(runCounts, "failed") || 0, "Failed")}
          ${renderStatTile(read(runCounts, "cancelled") || 0, "Cancelled")}
        </div>
      </article>
      <article class="span-7">
        <p class="section-kicker">Latest Run</p>
        ${latestRun ? `
          <h3 class="section-title">${escapeHtml(read(latestRun, "taskTitle") || read(latestRun, "runId"))}</h3>
          <p>${escapeHtml(read(latestRun, "eventBody") || read(latestRun, "status") || "暂无事件正文")}</p>
          <div class="chip-row">
            ${renderChip(read(latestRun, "status") || "UNKNOWN")}
            ${renderChip(read(latestRun, "runKind") || "IMPL")}
            ${renderChip(read(latestRun, "workerId") || "unassigned")}
          </div>
        ` : `<p class="muted">当前没有可展示的 run 摘要。</p>`}
      </article>
      <article class="span-5">
        <p class="section-kicker">Completion Gate</p>
        <ul class="list-plain">
          ${arrayOf(progress, "completionBlockers").length > 0
            ? arrayOf(progress, "completionBlockers").map((item) => `<li class="chip">${escapeHtml(item)}</li>`).join("")
            : `<li class="chip">当前没有阻塞项</li>`}
        </ul>
      </article>
    </section>
  `;
}

function renderRequirementView() {
  const detail = state.sessionDetails.get(state.activeSessionId);
  const doc = getRequirementDoc(detail);
  const composer = getRequirementComposer(state.activeSessionId);
  const content = state.requirementContentBySession.get(state.activeSessionId) || "";
  return `
    <section class="studio-layout">
      <article class="studio-panel span-3">
        <p class="section-kicker">Requirement Ledger</p>
        <h3>${escapeHtml(doc ? read(doc, "title") : "未创建文档")}</h3>
        <div class="section-body">
          <div class="detail-line"><span>状态</span>${renderStatusPill(doc ? read(doc, "status") : "DRAFTING", "phase")}</div>
          <div class="detail-line"><span>当前版本</span><strong>${doc ? read(doc, "currentVersion") || 0 : 0}</strong></div>
          <div class="detail-line"><span>确认版本</span><strong>${doc ? read(doc, "confirmedVersion") || "-" : "-"}</strong></div>
          <div class="detail-line"><span>更新时间</span><strong>${formatDateTime(doc ? read(doc, "updatedAt") : null)}</strong></div>
        </div>
      </article>
      <article class="input-shell span-6">
        <label class="field-label">Requirement Title</label>
        <input data-model="requirement-title" value="${escapeHtml(composer.title || "")}" placeholder="需求标题">
        <label class="field-label">Markdown Content</label>
        <textarea data-model="requirement-content" placeholder="在这里直接编辑 requirement markdown">${escapeHtml(content)}</textarea>
        <div class="button-row">
          ${renderActionButton("保存新版本", "save-requirement", "primary")}
          ${renderActionButton("确认 Requirement", "confirm-requirement", "ghost", !doc)}
        </div>
      </article>
      <article class="studio-panel span-3 assistant-panel">
        <p class="section-kicker">Requirement Agent</p>
        <label class="field-label">输入补充信息</label>
        <textarea data-model="requirement-user-input" placeholder="描述增量、澄清点、范围与验收标准">${escapeHtml(composer.userInput || "")}</textarea>
        <div class="button-row">
          ${renderActionButton("生成草稿", "create-draft", "primary", false, { persist: "true" })}
          ${renderActionButton("仅分析缺口", "create-draft", "ghost", false, { persist: "false" })}
        </div>
        ${composer.assistantMessage ? `<div class="assistant-message">${escapeHtml(composer.assistantMessage)}</div>` : `<p class="field-hint">这里会展示 LLM 的澄清结论与草稿提示。</p>`}
        ${composer.missingInformation && composer.missingInformation.length > 0 ? `<div class="chip-row">${composer.missingInformation.map((item) => renderChip(item)).join("")}</div>` : ""}
      </article>
    </section>
  `;
}

function renderTicketView() {
  const inbox = state.ticketInboxBySession.get(state.activeSessionId);
  if (!inbox) {
    return renderEmptyState("正在读取 Ticket Inbox", "这里会聚合当前 Session 的 clarification / decision / arch review 提请。");
  }
  const tickets = arrayOf(inbox, "tickets");
  const selected = getSelectedTicket(state.activeSessionId, tickets);
  return `
    <section class="ticket-grid">
      <div class="ticket-list">
        ${tickets.length > 0 ? tickets.map((ticket) => `
          <button type="button" class="ticket-card" data-detail-kind="ticket" data-detail-id="${escapeHtml(getTicketId(ticket))}">
            <p class="section-kicker">${escapeHtml(getRequestKind(ticket) || read(ticket, "type") || "Ticket")}</p>
            <h3>${escapeHtml(read(ticket, "title") || getTicketId(ticket))}</h3>
            <p>${escapeHtml(inferTicketQuestion(ticket))}</p>
            <div class="ticket-card__footer">
              ${renderStatusPill(read(ticket, "status"), "status")}
              <span>${escapeHtml(formatDateTime(read(ticket, "updatedAt") || read(ticket, "latestEventAt")))}</span>
            </div>
          </button>
        `).join("") : renderEmptyState("当前没有 Tickets", "没有待处理提请时，这里会保持空白。")}
      </div>
      <div class="drawer-stack">
        ${selected ? `
          <article class="drawer-card">
            <p class="section-kicker">Selected Ticket</p>
            <h4>${escapeHtml(read(selected, "title") || getTicketId(selected))}</h4>
            <p>${escapeHtml(inferTicketQuestion(selected))}</p>
            <div class="chip-row">
              ${renderChip(read(selected, "type") || "UNKNOWN")}
              ${renderChip(read(selected, "status") || "UNKNOWN")}
              ${renderChip(read(selected, "sourceRunId") || "no-run")}
            </div>
          </article>
          <article class="input-shell">
            <label class="field-label">回复内容</label>
            <textarea data-model="ticket-reply" data-ticket-id="${escapeHtml(getTicketId(selected))}" placeholder="直接回复系统的 clarification / decision 请求">${escapeHtml(state.ticketReplyById.get(getTicketId(selected)) || "")}</textarea>
            <div class="button-row">
              ${renderActionButton("提交响应", "reply-ticket", "primary", !read(selected, "needsUserAction"), { ticketId: getTicketId(selected) })}
            </div>
          </article>
        ` : renderEmptyState("未选择 Ticket", "点击左侧 ticket 卡片查看详情并回复。")}
      </div>
    </section>
  `;
}

function renderTaskBoardView() {
  const board = state.taskBoardBySession.get(state.activeSessionId);
  if (!board) {
    return renderEmptyState("正在读取 Task Board", "这里会显示按模块组织的任务看板。");
  }
  if (board.unavailableReason) {
    return renderEmptyState("Task Board 暂不可用", board.unavailableReason);
  }

  const tasks = flattenTasks(board);
  const selected = getSelectedTask(state.activeSessionId, tasks);
  return `
    <section class="tasks-layout">
      <div class="lane-stack">
        ${arrayOf(board, "modules").map((module) => `
          <section class="lane-shell">
            <div class="lane-shell__header">
              <div>
                <p class="section-kicker">${escapeHtml(read(module, "moduleId") || "")}</p>
                <h3>${escapeHtml(read(module, "moduleName") || "Unnamed Module")}</h3>
              </div>
              <span class="muted">${arrayOf(module, "tasks").length} tasks</span>
            </div>
            ${arrayOf(module, "tasks").map((task) => `
              <button type="button" class="lane-card" data-detail-kind="task" data-detail-id="${escapeHtml(read(task, "taskId") || "")}">
                <p class="section-kicker">${escapeHtml(read(task, "taskTemplateId") || "task")}</p>
                <h3>${escapeHtml(read(task, "title") || read(task, "taskId") || "Untitled Task")}</h3>
                <div class="lane-card__chips">
                  ${renderChip(read(task, "status") || "UNKNOWN")}
                  ${renderChip(read(task, "lastRunStatus") || "NO_RUN")}
                  ${renderChip(read(task, "latestContextStatus") || "NO_CONTEXT")}
                </div>
                <div class="lane-card__meta">
                  <span>${escapeHtml(read(task, "activeRunId") || read(task, "lastRunId") || "no-run")}</span>
                  <span>${escapeHtml(formatDateTime(read(task, "lastRunUpdatedAt") || read(task, "latestContextCompiledAt")))}</span>
                </div>
              </button>
            `).join("")}
          </section>
        `).join("")}
      </div>
      <div class="drawer-stack">
        <article class="drawer-card">
          <p class="section-kicker">Board Summary</p>
          <div class="stat-grid">
            ${renderStatTile(read(board, "totalTasks") || 0, "Tasks")}
            ${renderStatTile(read(board, "activeRuns") || 0, "Active Runs")}
            ${renderStatTile(arrayOf(board, "modules").length, "Modules")}
          </div>
        </article>
        ${selected ? renderTaskPreviewCard(selected) : renderEmptyState("未选择 Task", "点击任务卡片在右侧查看详细上下文。")}
      </div>
    </section>
  `;
}

function renderRunTimelineView() {
  const timeline = state.runTimelineBySession.get(state.activeSessionId);
  if (!timeline) {
    return renderEmptyState("正在读取 Run Timeline", "这里会显示最近的 run 事件序列。");
  }
  if (timeline.unavailableReason) {
    return renderEmptyState("Run Timeline 暂不可用", timeline.unavailableReason);
  }

  const items = arrayOf(timeline, "items");
  const selected = getSelectedRun(state.activeSessionId, items);
  return `
    <section class="tasks-layout">
      <div class="timeline-stack">
        ${items.map((item) => `
          <button type="button" class="timeline-card" data-detail-kind="run" data-detail-id="${escapeHtml(read(item, "runId") || "")}">
            <p class="section-kicker">${escapeHtml(read(item, "eventType") || "RUN_EVENT")}</p>
            <h3>${escapeHtml(read(item, "taskTitle") || read(item, "runId") || "Run")}</h3>
            <p>${escapeHtml(read(item, "eventBody") || read(item, "runStatus") || "无正文")}</p>
            <div class="ticket-card__footer">
              <div class="chip-row">
                ${renderChip(read(item, "runStatus") || "UNKNOWN")}
                ${renderChip(read(item, "runKind") || "IMPL")}
              </div>
              <span>${escapeHtml(formatDateTime(read(item, "eventCreatedAt") || read(item, "startedAt")))}</span>
            </div>
          </button>
        `).join("")}
      </div>
      <div class="drawer-stack">
        <article class="drawer-card">
          <p class="section-kicker">Timeline Summary</p>
          <div class="stat-grid">
            ${renderStatTile(read(timeline, "totalItems") || items.length, "Items")}
            ${renderStatTile(items.filter((item) => read(item, "runStatus") === "RUNNING").length, "Running")}
            ${renderStatTile(items.filter((item) => read(item, "eventType") === "RUN_FINISHED").length, "Finished")}
          </div>
        </article>
        ${selected ? renderRunPreviewCard(selected) : renderEmptyState("未选择 Run", "点击左侧事件卡片查看原始执行信息。")}
      </div>
    </section>
  `;
}

function renderDeliveryView() {
  const progress = state.progressBySession.get(state.activeSessionId);
  const delivery = read(progress, "delivery") || {};
  const clone = state.cloneRepoBySession.get(state.activeSessionId);
  return `
    <section class="delivery-layout">
      <article class="delivery-card span-7">
        <p class="section-kicker">Completion Gate</p>
        <h3>${read(progress, "canCompleteSession") ? "Session 可以完成" : "Session 还不能完成"}</h3>
        <p>${escapeHtml(read(progress, "blockerSummary") || "暂无阻塞摘要")}</p>
        <div class="chip-row">
          ${renderChip(`delivery_tag=${read(delivery, "deliveryTagPresent") ? "yes" : "no"}`)}
          ${renderChip(`done=${read(delivery, "doneTaskCount") || 0}`)}
          ${renderChip(`delivered=${read(delivery, "deliveredTaskCount") || 0}`)}
        </div>
        <ul class="list-plain">
          ${arrayOf(progress, "completionBlockers").length > 0
            ? arrayOf(progress, "completionBlockers").map((item) => `<li class="chip">${escapeHtml(item)}</li>`).join("")
            : `<li class="chip">当前没有 completion blocker</li>`}
        </ul>
      </article>
      <article class="delivery-card span-5 clone-card">
        <p class="section-kicker">Clone Repo</p>
        ${clone ? `
          <h3>${escapeHtml(read(clone, "repositoryName") || "delivery-repo")}</h3>
          <div class="code-block">${escapeHtml(read(clone, "cloneCommand") || "")}</div>
          <p>${escapeHtml(read(clone, "cloneUrl") || "")}</p>
        ` : `<p class="muted">还没有可用的 clone 地址，后端会在交付条件满足后发布。</p>`}
        <div class="button-row">
          ${renderActionButton(clone ? "重新发布地址" : "发布 Clone 地址", "publish-clone", "primary")}
        </div>
      </article>
    </section>
  `;
}

function renderRuntimeView() {
  if (!state.runtimeEditor) {
    return renderEmptyState("正在读取 Runtime Config", "这里会展示当前 requirement / worker LLM profile。");
  }
  return `
    <section class="ops-layout">
      <article class="ops-card span-7">
        <p class="section-kicker">Runtime Editor</p>
        <div class="input-shell">
          <label class="field-label">Output Language</label>
          <input data-model="output-language" value="${escapeHtml(state.runtimeEditor.outputLanguage)}">
          ${renderRuntimeProfileFields("requirementLlm", "Requirement LLM", state.runtimeEditor.requirementLlm)}
          ${renderRuntimeProfileFields("workerRuntimeLlm", "Worker Runtime LLM", state.runtimeEditor.workerRuntimeLlm)}
          <div class="button-row">
            ${renderActionButton("读取配置", "refresh-runtime", "ghost")}
            ${renderActionButton("探测连通性", "test-runtime", "ghost")}
            ${renderActionButton("应用配置", "apply-runtime", "primary")}
          </div>
        </div>
      </article>
      <article class="ops-card span-5">
        <p class="section-kicker">Current Runtime</p>
        <h3>Version ${read(state.runtimeConfig, "version") || "-"}</h3>
        <div class="drawer-stack">
          ${renderRuntimeSummaryCard("Requirement", read(state.runtimeConfig, "requirementLlm"))}
          ${renderRuntimeSummaryCard("Worker", read(state.runtimeConfig, "workerRuntimeLlm"))}
          ${state.runtimeProbe ? `<div class="json-block">${escapeHtml(prettyJson(state.runtimeProbe))}</div>` : `<p class="muted">探测结果会显示在这里。</p>`}
        </div>
      </article>
    </section>
  `;
}

function renderWorkforceView() {
  const workers = state.workers;
  if (!workers) {
    return renderEmptyState("正在读取 Worker Pool", "这里会展示 worker 状态、toolpacks 与自动化入口。");
  }
  return `
    <section class="ops-layout">
      <article class="ops-card span-4">
        <p class="section-kicker">Pool Summary</p>
        <div class="stat-grid">
          ${renderStatTile(read(workers, "totalWorkers") || 0, "Total")}
          ${renderStatTile(read(workers, "readyWorkers") || 0, "Ready")}
          ${renderStatTile(read(workers, "disabledWorkers") || 0, "Disabled")}
        </div>
      </article>
      <article class="ops-card span-8">
        <p class="section-kicker">Automations</p>
        <div class="button-row">
          ${renderActionButton("刷新 Workers", "refresh-workers", "ghost")}
          ${renderActionButton("Auto Provision", "auto-provision", "ghost")}
          ${renderActionButton("Auto Run", "auto-run", "primary")}
          ${renderActionButton("Lease Recovery", "lease-recovery", "ghost")}
          ${renderActionButton("Cleanup", "cleanup-workers", "ghost")}
        </div>
        ${state.lastAutomationResult ? `<div class="json-block">${escapeHtml(prettyJson(state.lastAutomationResult))}</div>` : `<p class="muted">最近一次自动化执行结果会显示在这里。</p>`}
      </article>
      <article class="ops-card span-12">
        <p class="section-kicker">Workers</p>
        <div class="lane-stack">
          ${arrayOf(workers, "workers").map((worker) => `
            <div class="lane-card">
              <p class="section-kicker">${escapeHtml(read(worker, "workerId") || "")}</p>
              <h3>${escapeHtml(read(worker, "status") || "UNKNOWN")}</h3>
              <div class="chip-row">${arrayOf(worker, "toolpackIds").map((item) => renderChip(item)).join("")}</div>
              <div class="lane-card__meta">
                <span>created ${escapeHtml(formatDateTime(read(worker, "createdAt")))}</span>
                <span>updated ${escapeHtml(formatDateTime(read(worker, "updatedAt")))}</span>
              </div>
            </div>
          `).join("")}
        </div>
      </article>
    </section>
  `;
}

function renderDetailDrawer() {
  if (!state.detail) {
    refs.detailDrawer.classList.add("is-empty");
    refs.detailTitle.textContent = "未选择明细";
    refs.detailContent.innerHTML = `<p>点击 ticket / task / run 卡片后，这里会显示更细的上下文。</p>`;
    return;
  }

  refs.detailDrawer.classList.remove("is-empty");
  const { title, body } = renderDetailBody(state.detail);
  refs.detailTitle.textContent = title;
  refs.detailContent.innerHTML = body;
}

function renderDetailBody(detail) {
  if (detail.kind === "ticket") {
    const ticket = findTicketById(state.activeSessionId, detail.id);
    const events = state.ticketEventsById.get(detail.id) || [];
    return {
      title: read(ticket, "title") || detail.id,
      body: `
        <div class="drawer-stack">
          <article class="drawer-card">
            <p class="section-kicker">Ticket Snapshot</p>
            <div class="chip-row">
              ${renderChip(read(ticket, "type") || "UNKNOWN")}
              ${renderChip(read(ticket, "status") || "UNKNOWN")}
              ${renderChip(read(ticket, "assigneeRole") || "unassigned")}
            </div>
            <p>${escapeHtml(inferTicketQuestion(ticket))}</p>
          </article>
          <article class="drawer-card">
            <p class="section-kicker">Payload</p>
            <div class="json-block">${escapeHtml(prettyJson(read(ticket, "payloadJson") || "{}"))}</div>
          </article>
          <article class="drawer-card">
            <p class="section-kicker">Events</p>
            ${events.length > 0 ? events.map((item) => `
              <div class="code-block">${escapeHtml(`${read(item, "eventType")} · ${formatDateTime(read(item, "createdAt"))}\n${read(item, "body") || ""}`)}</div>
            `).join("") : `<p class="muted">暂无已加载事件。</p>`}
          </article>
        </div>
      `,
    };
  }

  if (detail.kind === "task") {
    const task = findTaskById(state.activeSessionId, detail.id);
    return {
      title: read(task, "title") || detail.id,
      body: `
        <div class="drawer-stack">
          <article class="drawer-card">
            <p class="section-kicker">Task Snapshot</p>
            <div class="chip-row">
              ${renderChip(read(task, "status") || "UNKNOWN")}
              ${renderChip(read(task, "taskTemplateId") || "task")}
              ${renderChip(read(task, "activeRunId") || "no-active-run")}
            </div>
          </article>
          <article class="drawer-card">
            <p class="section-kicker">Context & Dependency</p>
            <div class="detail-line"><span>Context Snapshot</span><strong>${escapeHtml(read(task, "latestContextSnapshotId") || "-")}</strong></div>
            <div class="detail-line"><span>Context Status</span><strong>${escapeHtml(read(task, "latestContextStatus") || "-")}</strong></div>
            <div class="detail-line"><span>Dependency Count</span><strong>${arrayOf(task, "dependencyTaskIds").length}</strong></div>
            <div class="chip-row">${arrayOf(task, "dependencyTaskIds").map((item) => renderChip(item)).join("") || renderChip("no-dependency")}</div>
          </article>
          <article class="drawer-card">
            <p class="section-kicker">Delivery & Verify</p>
            <div class="detail-line"><span>Latest Commit</span><strong>${escapeHtml(read(task, "latestDeliveryCommit") || "-")}</strong></div>
            <div class="detail-line"><span>Verify Run</span><strong>${escapeHtml(read(task, "latestVerifyRunId") || "-")}</strong></div>
            <div class="detail-line"><span>Verify Status</span><strong>${escapeHtml(read(task, "latestVerifyStatus") || "-")}</strong></div>
          </article>
        </div>
      `,
    };
  }

  const run = findRunById(state.activeSessionId, detail.id);
  return {
    title: read(run, "runId") || detail.id,
    body: `
      <div class="drawer-stack">
        <article class="drawer-card">
          <p class="section-kicker">Run Snapshot</p>
          <div class="chip-row">
            ${renderChip(read(run, "runStatus") || "UNKNOWN")}
            ${renderChip(read(run, "runKind") || "IMPL")}
            ${renderChip(read(run, "workerId") || "no-worker")}
          </div>
          <p>${escapeHtml(read(run, "eventBody") || "无事件正文")}</p>
        </article>
        <article class="drawer-card">
          <p class="section-kicker">Raw Event Data</p>
          <div class="json-block">${escapeHtml(prettyJson(read(run, "eventDataJson") || "{}"))}</div>
        </article>
        <article class="drawer-card">
          <p class="section-kicker">Git Context</p>
          <div class="detail-line"><span>Branch</span><strong>${escapeHtml(read(run, "branchName") || "-")}</strong></div>
          <div class="detail-line"><span>Started</span><strong>${escapeHtml(formatDateTime(read(run, "startedAt")))}</strong></div>
          <div class="detail-line"><span>Finished</span><strong>${escapeHtml(formatDateTime(read(run, "finishedAt")))}</strong></div>
        </article>
      </div>
    `,
  };
}

async function openDetail(kind, id) {
  if (!kind || !id) {
    return;
  }
  if (kind === "ticket") {
    state.selectedTicketIdBySession.set(state.activeSessionId, id);
    await loadTicketEvents(id);
  }
  if (kind === "task") {
    state.selectedTaskIdBySession.set(state.activeSessionId, id);
  }
  if (kind === "run") {
    state.selectedRunIdBySession.set(state.activeSessionId, id);
  }
  state.detail = { kind, id };
  render();
}

async function apiRequest(path, options = {}) {
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
      const message = typeof data === "object" && data ? read(data, "message") || read(data, "error") : text;
      throw new Error(message || `${options.method || "GET"} ${path} failed with ${response.status}`);
    }
    return data;
  } finally {
    window.clearTimeout(timeout);
  }
}

async function withErrorToast(work) {
  try {
    await work();
  } catch (error) {
    showToast(error.message || String(error), true);
  }
}

function getRequirementComposer(sessionId) {
  if (!state.requirementComposerBySession.has(sessionId)) {
    state.requirementComposerBySession.set(sessionId, {
      title: "",
      userInput: "",
      assistantMessage: "",
      readyToDraft: false,
      missingInformation: [],
    });
  }
  return state.requirementComposerBySession.get(sessionId);
}

function getSelectedTicket(sessionId, tickets) {
  ensureSelectedTicket(sessionId, tickets);
  return tickets.find((ticket) => getTicketId(ticket) === state.selectedTicketIdBySession.get(sessionId)) || null;
}

function getSelectedTask(sessionId, tasks) {
  ensureSelectedTask(sessionId, tasks);
  return tasks.find((task) => read(task, "taskId") === state.selectedTaskIdBySession.get(sessionId)) || null;
}

function getSelectedRun(sessionId, items) {
  ensureSelectedRun(sessionId, items);
  return items.find((item) => read(item, "runId") === state.selectedRunIdBySession.get(sessionId)) || null;
}

function ensureSelectedTicket(sessionId, tickets) {
  if (!state.selectedTicketIdBySession.get(sessionId) && tickets[0]) {
    state.selectedTicketIdBySession.set(sessionId, getTicketId(tickets[0]));
  }
}

function ensureSelectedTask(sessionId, tasks) {
  if (!state.selectedTaskIdBySession.get(sessionId) && tasks[0]) {
    state.selectedTaskIdBySession.set(sessionId, read(tasks[0], "taskId"));
  }
}

function ensureSelectedRun(sessionId, items) {
  if (!state.selectedRunIdBySession.get(sessionId) && items[0]) {
    state.selectedRunIdBySession.set(sessionId, read(items[0], "runId"));
  }
}

function flattenTasks(board) {
  return arrayOf(board, "modules").flatMap((module) => arrayOf(module, "tasks"));
}

function findTicketById(sessionId, ticketId) {
  return arrayOf(state.ticketInboxBySession.get(sessionId), "tickets").find((ticket) => getTicketId(ticket) === ticketId) || null;
}

function findTaskById(sessionId, taskId) {
  return flattenTasks(state.taskBoardBySession.get(sessionId) || {}).find((task) => read(task, "taskId") === taskId) || null;
}

function findRunById(sessionId, runId) {
  return arrayOf(state.runTimelineBySession.get(sessionId), "items").find((run) => read(run, "runId") === runId) || null;
}

function getRequirementDoc(source) {
  return read(source, "currentRequirementDoc");
}

function getSessionId(session) {
  return read(session, "sessionId") || "";
}

function getSessionTitle(session) {
  return read(session, "title") || "Untitled Session";
}

function getDocId(doc) {
  return read(doc, "docId") || "";
}

function getTicketId(ticket) {
  return read(ticket, "ticketId") || "";
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
  if (waitingUser > 0) {
    return "优先处理 WAITING_USER ticket。";
  }
  if (!doc) {
    return "先生成 requirement 草稿。";
  }
  if (read(doc, "status") !== "CONFIRMED") {
    return "审阅并确认 requirement。";
  }
  return "继续观察任务与运行状态。";
}

function countStatus(items, status) {
  return items.filter((item) => read(item, "status") === status).length;
}

function inferRequestKind(ticket) {
  const payload = safeParseJson(read(ticket, "payloadJson"));
  return payload ? payload.request_kind || payload.requestKind || null : null;
}

function inferTicketQuestion(ticket) {
  return read(ticket, "question") || read(ticket, "latestEventBody") || read(ticket, "title") || "需要用户进一步提供信息。";
}

function getRequestKind(ticket) {
  return read(ticket, "requestKind") || inferRequestKind(ticket);
}

function fallbackSessionSummary(requirement) {
  if (!requirement) {
    return "还没有 requirement doc。";
  }
  return `Requirement ${read(requirement, "status") || "UNKNOWN"} / v${read(requirement, "currentVersion") || 0}`;
}

function renderWorkspaceButton(id, label) {
  return `<button type="button" class="workspace-switch__button ${state.activeWorkspace === id ? "is-active" : ""}" data-workspace="${id}">${escapeHtml(label)}</button>`;
}

function renderMetricChip(label, value) {
  return `<div class="metric-chip"><span class="metric-chip__label">${escapeHtml(label)}</span><div class="metric-chip__value">${escapeHtml(String(value))}</div></div>`;
}

function renderMetricTile(value, label) {
  return `<div class="metric-tile"><strong>${escapeHtml(String(value))}</strong><span>${escapeHtml(label)}</span></div>`;
}

function renderStatTile(value, label) {
  return `<div class="stat-tile"><strong>${escapeHtml(String(value))}</strong><span>${escapeHtml(label)}</span></div>`;
}

function renderActionButton(label, action, tone, disabled = false, dataset = {}) {
  const className = tone === "primary" ? "primary-button" : tone === "danger" ? "danger-button" : "ghost-button";
  const attributes = Object.entries(dataset)
    .map(([key, value]) => `data-${escapeHtml(key)}="${escapeHtml(String(value))}"`)
    .join(" ");
  return `<button type="button" class="${className}" data-action="${action}" ${attributes} ${disabled ? "disabled" : ""}>${escapeHtml(label)}</button>`;
}

function renderStatusPill(value, kind) {
  return `<span class="${kind === "phase" ? "phase-pill" : "status-pill"}" data-tone="${statusTone(value)}">${escapeHtml(value || "UNKNOWN")}</span>`;
}

function renderChip(value) {
  return `<span class="chip">${escapeHtml(String(value))}</span>`;
}

function renderEmptyState(title, body) {
  return `<div class="empty-state"><div><strong>${escapeHtml(title)}</strong><p>${escapeHtml(body)}</p></div></div>`;
}

function renderRuntimeProfileFields(profileKey, label, profile) {
  return `
    <label class="field-label">${escapeHtml(label)} Provider</label>
    <input data-profile="${profileKey}" data-model="provider" value="${escapeHtml(profile.provider)}">
    <label class="field-label">${escapeHtml(label)} Framework</label>
    <input data-profile="${profileKey}" data-model="framework" value="${escapeHtml(profile.framework)}">
    <label class="field-label">${escapeHtml(label)} Base URL</label>
    <input data-profile="${profileKey}" data-model="base-url" value="${escapeHtml(profile.baseUrl)}">
    <label class="field-label">${escapeHtml(label)} Model</label>
    <input data-profile="${profileKey}" data-model="model-name" value="${escapeHtml(profile.model)}">
    <label class="field-label">${escapeHtml(label)} Timeout</label>
    <input data-profile="${profileKey}" data-model="timeout-ms" value="${escapeHtml(profile.timeoutMs)}">
    <label class="field-label">${escapeHtml(label)} API Key</label>
    <input data-profile="${profileKey}" data-model="api-key" value="" placeholder="${escapeHtml(profile.apiKeyMasked || "保持现状")}">
  `;
}

function renderRuntimeSummaryCard(label, profile) {
  return `
    <div class="drawer-card">
      <p class="section-kicker">${escapeHtml(label)}</p>
      <div class="detail-line"><span>Provider</span><strong>${escapeHtml(read(profile, "provider") || "-")}</strong></div>
      <div class="detail-line"><span>Model</span><strong>${escapeHtml(read(profile, "model") || "-")}</strong></div>
      <div class="detail-line"><span>Base URL</span><strong>${escapeHtml(read(profile, "baseUrl") || "-")}</strong></div>
      <div class="detail-line"><span>API Key</span><strong>${escapeHtml(read(profile, "apiKeyMasked") || "-")}</strong></div>
    </div>
  `;
}

function renderTaskPreviewCard(task) {
  return `
    <article class="drawer-card">
      <p class="section-kicker">Selected Task</p>
      <h4>${escapeHtml(read(task, "title") || read(task, "taskId") || "Task")}</h4>
      <div class="chip-row">
        ${renderChip(read(task, "status") || "UNKNOWN")}
        ${renderChip(read(task, "lastRunStatus") || "NO_RUN")}
        ${renderChip(read(task, "latestVerifyStatus") || "NO_VERIFY")}
      </div>
      <div class="detail-line"><span>Last Run</span><strong>${escapeHtml(read(task, "lastRunId") || "-")}</strong></div>
      <div class="detail-line"><span>Delivery Commit</span><strong>${escapeHtml(read(task, "latestDeliveryCommit") || "-")}</strong></div>
    </article>
  `;
}

function renderRunPreviewCard(run) {
  return `
    <article class="drawer-card">
      <p class="section-kicker">Selected Run</p>
      <h4>${escapeHtml(read(run, "runId") || "Run")}</h4>
      <p>${escapeHtml(read(run, "eventBody") || "无事件正文")}</p>
      <div class="detail-line"><span>Task</span><strong>${escapeHtml(read(run, "taskTitle") || read(run, "taskId") || "-")}</strong></div>
      <div class="detail-line"><span>Worker</span><strong>${escapeHtml(read(run, "workerId") || "-")}</strong></div>
      <div class="detail-line"><span>Branch</span><strong>${escapeHtml(read(run, "branchName") || "-")}</strong></div>
    </article>
  `;
}

function read(source, name) {
  if (!source) {
    return null;
  }
  if (source[name] !== undefined && source[name] !== null) {
    return source[name];
  }
  const snake = name.replace(/[A-Z]/g, (part) => `_${part.toLowerCase()}`);
  return source[snake] !== undefined ? source[snake] : null;
}

function arrayOf(source, name) {
  const value = read(source, name);
  return Array.isArray(value) ? value : [];
}

function safeParseJson(value) {
  if (!value || typeof value !== "string") {
    return typeof value === "object" ? value : null;
  }
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function prettyJson(value) {
  const parsed = typeof value === "string" ? safeParseJson(value) : value;
  if (parsed) {
    return JSON.stringify(parsed, null, 2);
  }
  return String(value || "");
}

function formatDateTime(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

function statusTone(value) {
  const status = String(value || "").toUpperCase();
  if (["RUNNING", "ACTIVE", "READY", "SUCCEEDED", "CONFIRMED"].includes(status)) {
    return "active";
  }
  if (["WAITING_USER", "WAITING_FOREMAN", "WAITING_WORKER", "DELIVERED", "REVIEWING", "DRAFTING"].includes(status)) {
    return "waiting";
  }
  if (["FAILED", "BLOCKED", "CANCELLED", "DISABLED", "MISSING"].includes(status)) {
    return "danger";
  }
  if (["DONE", "COMPLETED"].includes(status)) {
    return "done";
  }
  return "done";
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function showToast(message, isError = false) {
  refs.toast.textContent = message;
  refs.toast.classList.remove("hidden");
  refs.toast.classList.toggle("is-danger", Boolean(isError));
  refs.toast.classList.toggle("is-success", !isError);
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => refs.toast.classList.add("hidden"), 2600);
}

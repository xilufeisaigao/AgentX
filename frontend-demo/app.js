const API_TIMEOUT_MS = 240000;
const DEFAULT_GIT_URL = "git@github.com:your-org/agentx-backend.git";
const DEFAULT_UI_LANGUAGE = "zh-CN";
const SESSION_PREVIEW_LIMIT = 20;

const state = {
  sessions: [],
  activeSessionId: null,
  showAllSessions: false,
  messagesBySession: new Map(),
  tickets: [],
  ticketEventsById: new Map(),
  openTicketIds: new Set(),
  ticketDraftsById: new Map(),
  workersById: new Map(),
  uiLanguage: DEFAULT_UI_LANGUAGE,
  runtimeSummary: {
    claimedRuns: 0,
    succeededRuns: 0,
    needInputRuns: 0,
    failedRuns: 0,
  },
  rightPanelOpenOrder: [],
  clonePublicationBySession: new Map(),
  modalShown: false,
  refreshTimer: null,
};

const refs = {
  sessionList: document.getElementById("sessionList"),
  sessionCount: document.getElementById("sessionCount"),
  toggleSessionBtn: document.getElementById("toggleSessionBtn"),
  newSessionBtn: document.getElementById("newSessionBtn"),
  activeSessionTitle: document.getElementById("activeSessionTitle"),
  activeSessionMeta: document.getElementById("activeSessionMeta"),
  chatFeed: document.getElementById("chatFeed"),
  chatForm: document.getElementById("chatForm"),
  messageInput: document.getElementById("messageInput"),
  sendBtn: document.getElementById("sendBtn"),
  refreshBtn: document.getElementById("refreshBtn"),
  confirmBtn: document.getElementById("confirmBtn"),
  advanceBtn: document.getElementById("advanceBtn"),
  ticketList: document.getElementById("ticketList"),
  refreshTicketBtn: document.getElementById("refreshTicketBtn"),
  progressList: document.getElementById("progressList"),
  workerList: document.getElementById("workerList"),
  autoProvisionBtn: document.getElementById("autoProvisionBtn"),
  autoRunBtn: document.getElementById("autoRunBtn"),
  toast: document.getElementById("toast"),
  gitModal: document.getElementById("gitModal"),
  gitAddress: document.getElementById("gitAddress"),
  gitCloneCommand: document.getElementById("gitCloneCommand"),
  gitCloneMeta: document.getElementById("gitCloneMeta"),
  gitAddressInput: document.getElementById("gitAddressInput"),
  copyGitBtn: document.getElementById("copyGitBtn"),
  closeModalBtn: document.getElementById("closeModalBtn"),
  languageSelect: document.getElementById("languageSelect"),
  runtimeRefreshBtn: document.getElementById("runtimeRefreshBtn"),
  runtimeTestBtn: document.getElementById("runtimeTestBtn"),
  runtimeApplyBtn: document.getElementById("runtimeApplyBtn"),
  runtimeConfigStatus: document.getElementById("runtimeConfigStatus"),
  configOutputLanguage: document.getElementById("configOutputLanguage"),
  reqProvider: document.getElementById("reqProvider"),
  reqBaseUrl: document.getElementById("reqBaseUrl"),
  reqModel: document.getElementById("reqModel"),
  reqApiKey: document.getElementById("reqApiKey"),
  workerProvider: document.getElementById("workerProvider"),
  workerBaseUrl: document.getElementById("workerBaseUrl"),
  workerModel: document.getElementById("workerModel"),
  workerApiKey: document.getElementById("workerApiKey"),
  rightPanelToggles: Array.from(document.querySelectorAll(".right-collapse-toggle")),
};

document.addEventListener("DOMContentLoaded", () => {
  initializeRightPanels();
  wireEvents();
  void bootstrap();
});

function wireEvents() {
  refs.newSessionBtn.addEventListener("click", () => void createSession(false));
  refs.toggleSessionBtn.addEventListener("click", () => {
    state.showAllSessions = !state.showAllSessions;
    renderSessions();
  });
  refs.refreshBtn.addEventListener("click", () => void refreshActiveSession(true));
  refs.confirmBtn.addEventListener("click", () => void confirmRequirement(false));
  refs.advanceBtn.addEventListener("click", () => void advanceOneStep());
  refs.refreshTicketBtn.addEventListener("click", () => void refreshTickets(true));
  refs.autoProvisionBtn.addEventListener("click", () => void triggerAutoProvision());
  refs.autoRunBtn.addEventListener("click", () => void triggerAutoRun());
  refs.chatForm.addEventListener("submit", (event) => {
    event.preventDefault();
    void sendChatMessage();
  });
  refs.copyGitBtn.addEventListener("click", () => void copyGitUrl());
  refs.closeModalBtn.addEventListener("click", () => closeGitModal());
  refs.gitAddressInput.addEventListener("change", () => persistGitAddress());
  refs.languageSelect.addEventListener("change", () => handleLanguageChange());
  refs.runtimeRefreshBtn.addEventListener("click", () => void loadRuntimeConfig(true));
  refs.runtimeTestBtn.addEventListener("click", () => void testRuntimeConfig());
  refs.runtimeApplyBtn.addEventListener("click", () => void applyRuntimeConfig());
  refs.rightPanelToggles.forEach((toggle) => {
    toggle.addEventListener("click", () => {
      const panelId = toggle.dataset.rightToggle;
      if (!panelId) {
        return;
      }
      toggleRightPanel(panelId);
    });
  });
}

function initializeRightPanels() {
  const panels = getRightPanels();
  state.rightPanelOpenOrder = [];
  panels.forEach((panel) => {
    const panelId = panel.dataset.rightPanelId;
    if (!panelId) {
      return;
    }
    if (!panel.classList.contains("is-collapsed")) {
      state.rightPanelOpenOrder.push(panelId);
    }
    updateRightPanelToggleState(panel);
  });
  enforceRightPanelOpenLimit();
}

function getRightPanels() {
  return Array.from(document.querySelectorAll(".right-collapsible"));
}

function findRightPanel(panelId) {
  return document.querySelector(`.right-collapsible[data-right-panel-id="${panelId}"]`);
}

function toggleRightPanel(panelId) {
  const panel = findRightPanel(panelId);
  if (!panel) {
    return;
  }
  if (panel.classList.contains("is-collapsed")) {
    openRightPanel(panelId);
    return;
  }
  closeRightPanel(panelId);
}

function openRightPanel(panelId) {
  const panel = findRightPanel(panelId);
  if (!panel) {
    return;
  }
  panel.classList.remove("is-collapsed");
  touchRightPanelOrder(panelId);
  enforceRightPanelOpenLimit(panelId);
  updateRightPanelToggleState(panel);
}

function closeRightPanel(panelId) {
  const panel = findRightPanel(panelId);
  if (!panel) {
    return;
  }
  panel.classList.add("is-collapsed");
  state.rightPanelOpenOrder = state.rightPanelOpenOrder.filter((id) => id !== panelId);
  updateRightPanelToggleState(panel);
}

function touchRightPanelOrder(panelId) {
  state.rightPanelOpenOrder = state.rightPanelOpenOrder.filter((id) => id !== panelId);
  state.rightPanelOpenOrder.push(panelId);
}

function enforceRightPanelOpenLimit(preferredPanelId = "") {
  const isOpen = (id) => {
    const panel = findRightPanel(id);
    return panel && !panel.classList.contains("is-collapsed");
  };
  state.rightPanelOpenOrder = state.rightPanelOpenOrder.filter((id) => isOpen(id));
  while (state.rightPanelOpenOrder.length > 2) {
    let closeId = state.rightPanelOpenOrder[0];
    if (closeId === preferredPanelId && state.rightPanelOpenOrder.length > 1) {
      closeId = state.rightPanelOpenOrder[1];
    }
    const panel = findRightPanel(closeId);
    if (panel) {
      panel.classList.add("is-collapsed");
      updateRightPanelToggleState(panel);
    }
    state.rightPanelOpenOrder = state.rightPanelOpenOrder.filter((id) => id !== closeId);
  }
}

function updateRightPanelToggleState(panel) {
  const toggle = panel.querySelector(".right-collapse-toggle");
  if (!toggle) {
    return;
  }
  const collapsed = panel.classList.contains("is-collapsed");
  toggle.setAttribute("aria-expanded", collapsed ? "false" : "true");
  const indicator = toggle.querySelector(".right-collapse-indicator");
  if (indicator) {
    indicator.textContent = collapsed ? "展开" : "收起";
  }
}

async function bootstrap() {
  const initialGitUrl = localStorage.getItem("agentx_git_url") || DEFAULT_GIT_URL;
  refs.gitAddressInput.value = initialGitUrl;
  syncCloneDisplay(initialGitUrl);
  state.uiLanguage = normalizeLanguageCode(localStorage.getItem("agentx_ui_language"));
  refs.languageSelect.value = state.uiLanguage;
  refs.configOutputLanguage.value = state.uiLanguage;

  await loadRuntimeConfig(false);

  try {
    await loadSessions();
    if (state.sessions.length === 0) {
      await createSession(true);
    } else {
      state.activeSessionId = state.sessions[0].session_id;
      if (!state.messagesBySession.has(state.activeSessionId)) {
        state.messagesBySession.set(state.activeSessionId, []);
      }
      await refreshActiveSession(false);
    }
  } catch (error) {
    showToast(String(error.message || error), true);
  } finally {
    render();
    startAutoRefresh();
  }
}

function startAutoRefresh() {
  if (state.refreshTimer) {
    clearInterval(state.refreshTimer);
  }
  state.refreshTimer = setInterval(() => {
    void refreshActiveSession(false);
  }, 12000);
}

async function apiRequest(path, options = {}) {
  const method = options.method || "GET";
  const query = options.query || null;
  const body = options.body;
  const url = new URL(path, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value).trim() !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), API_TIMEOUT_MS);
  const headers = {
    "X-AgentX-Language": state.uiLanguage || DEFAULT_UI_LANGUAGE,
  };
  if (body) {
    headers["Content-Type"] = "application/json";
  }
  try {
    const response = await fetch(url, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    clearTimeout(timeoutId);

    if (response.status === 204) {
      return { status: response.status, data: null };
    }

    let data = null;
    const raw = await response.text();
    if (raw) {
      try {
        data = JSON.parse(raw);
      } catch {
        data = { raw };
      }
    }

    if (!response.ok) {
      const detail = data && (data.message || data.code || data.raw) ? `: ${data.message || data.code || data.raw}` : "";
      throw new Error(`${method} ${path} failed (${response.status}${detail})`);
    }
    return { status: response.status, data };
  } catch (error) {
    clearTimeout(timeoutId);
    if (error.name === "AbortError") {
      throw new Error(`${method} ${path} timeout after ${API_TIMEOUT_MS / 1000}s`);
    }
    throw error;
  }
}

async function loadRuntimeConfig(showNotice) {
  try {
    const { data } = await apiRequest("/api/v0/runtime/llm-config");
    applyRuntimeConfigToForm(data);
    if (showNotice) {
      showToast("运行时配置已读取");
    }
  } catch (error) {
    refs.runtimeConfigStatus.textContent = `读取失败: ${error.message}`;
    refs.runtimeConfigStatus.classList.add("error");
    if (showNotice) {
      showToast(String(error.message || error), true);
    }
  }
}

async function testRuntimeConfig() {
  refs.runtimeTestBtn.disabled = true;
  refs.runtimeApplyBtn.disabled = true;
  refs.runtimeConfigStatus.textContent = "连通性测试中...";
  refs.runtimeConfigStatus.classList.remove("error");
  try {
    const payload = buildRuntimeConfigPayload(true);
    const { data } = await apiRequest("/api/v0/runtime/llm-config:test", {
      method: "POST",
      body: payload,
    });
    const req = data.requirement_llm || {};
    const wrk = data.worker_runtime_llm || {};
    const allOk = Boolean(data.all_ok);
    refs.runtimeConfigStatus.textContent =
      `测试${allOk ? "通过" : "失败"} | 需求:${req.ok ? "OK" : "FAIL"} (${req.latency_ms || 0}ms) | Worker:${wrk.ok ? "OK" : "FAIL"} (${wrk.latency_ms || 0}ms)`;
    refs.runtimeConfigStatus.classList.toggle("error", !allOk);
    if (!allOk) {
      const msg = req.error_message || wrk.error_message || "至少一个 LLM 连通性失败";
      showToast(msg, true);
    } else {
      showToast("连通性测试通过");
    }
  } catch (error) {
    refs.runtimeConfigStatus.textContent = `测试失败: ${error.message}`;
    refs.runtimeConfigStatus.classList.add("error");
    showToast(String(error.message || error), true);
  } finally {
    refs.runtimeTestBtn.disabled = false;
    refs.runtimeApplyBtn.disabled = false;
  }
}

async function applyRuntimeConfig() {
  refs.runtimeApplyBtn.disabled = true;
  refs.runtimeTestBtn.disabled = true;
  refs.runtimeConfigStatus.textContent = "正在应用配置...";
  refs.runtimeConfigStatus.classList.remove("error");
  try {
    const payload = buildRuntimeConfigPayload(true);
    const { data } = await apiRequest("/api/v0/runtime/llm-config:apply", {
      method: "POST",
      body: payload,
    });
    applyRuntimeConfigToForm(data);
    const language = normalizeLanguageCode(data.output_language);
    state.uiLanguage = language;
    refs.languageSelect.value = language;
    localStorage.setItem("agentx_ui_language", language);
    showToast("配置已应用，后续请求无需重启即可生效");
  } catch (error) {
    refs.runtimeConfigStatus.textContent = `应用失败: ${error.message}`;
    refs.runtimeConfigStatus.classList.add("error");
    showToast(String(error.message || error), true);
  } finally {
    refs.runtimeApplyBtn.disabled = false;
    refs.runtimeTestBtn.disabled = false;
  }
}

function buildRuntimeConfigPayload(includeApiKey) {
  const outputLanguage = normalizeLanguageCode(refs.configOutputLanguage.value);
  const reqApiKey = refs.reqApiKey.value.trim();
  const workerApiKey = refs.workerApiKey.value.trim();
  return {
    output_language: outputLanguage,
    requirement_llm: {
      provider: normalizeProvider(refs.reqProvider.value),
      framework: "langchain4j",
      base_url: refs.reqBaseUrl.value.trim(),
      model: refs.reqModel.value.trim(),
      api_key: includeApiKey && reqApiKey ? reqApiKey : undefined,
      timeout_ms: 120000,
    },
    worker_runtime_llm: {
      provider: normalizeProvider(refs.workerProvider.value),
      framework: "langchain4j",
      base_url: refs.workerBaseUrl.value.trim(),
      model: refs.workerModel.value.trim(),
      api_key: includeApiKey && workerApiKey ? workerApiKey : undefined,
      timeout_ms: 120000,
    },
  };
}

function applyRuntimeConfigToForm(config) {
  if (!config || typeof config !== "object") {
    return;
  }
  const requirement = config.requirement_llm || {};
  const worker = config.worker_runtime_llm || {};

  refs.configOutputLanguage.value = normalizeLanguageCode(config.output_language);
  refs.reqProvider.value = normalizeProvider(requirement.provider);
  refs.reqBaseUrl.value = toText(requirement.base_url || "");
  refs.reqModel.value = toText(requirement.model || "");
  refs.reqApiKey.value = "";

  refs.workerProvider.value = normalizeProvider(worker.provider);
  refs.workerBaseUrl.value = toText(worker.base_url || "");
  refs.workerModel.value = toText(worker.model || "");
  refs.workerApiKey.value = "";

  const reqKey = requirement.api_key_configured ? `已配置(${requirement.api_key_masked || "****"})` : "未配置";
  const workerKey = worker.api_key_configured ? `已配置(${worker.api_key_masked || "****"})` : "未配置";
  refs.runtimeConfigStatus.textContent =
    `当前配置 v${config.version || 1} | requirement=${refs.reqProvider.value}(${reqKey}) | worker=${refs.workerProvider.value}(${workerKey})`;
  refs.runtimeConfigStatus.classList.remove("error");
}

function normalizeProvider(value) {
  const normalized = String(value || "").trim().toLowerCase();
  if (normalized === "bailian") {
    return "bailian";
  }
  return "mock";
}

async function loadSessions() {
  const { data } = await apiRequest("/api/v0/sessions");
  state.sessions = Array.isArray(data) ? data.slice().sort(compareByUpdatedAtDesc) : [];
  if (!state.activeSessionId && state.sessions.length > 0) {
    state.activeSessionId = state.sessions[0].session_id;
  }
}

async function createSession(isAuto) {
  const now = new Date();
  const title = isAuto
    ? `Auto Session ${formatDateTime(now)}`
    : `Session ${formatDateTime(now)}`;
  const { data } = await apiRequest("/api/v0/sessions", {
    method: "POST",
    body: { title },
  });
  state.activeSessionId = data.session_id;
  if (!state.messagesBySession.has(data.session_id)) {
    state.messagesBySession.set(data.session_id, []);
  }
  addMessage(data.session_id, "system", `已创建会话: ${title}`);
  await loadSessions();
  await refreshActiveSession(true);
}

function compareByUpdatedAtDesc(a, b) {
  const aTime = new Date(a.updated_at || a.created_at || 0).getTime();
  const bTime = new Date(b.updated_at || b.created_at || 0).getTime();
  return bTime - aTime;
}

function getActiveSession() {
  return state.sessions.find((item) => item.session_id === state.activeSessionId) || null;
}

async function selectSession(sessionId) {
  state.activeSessionId = sessionId;
  if (!state.messagesBySession.has(sessionId)) {
    state.messagesBySession.set(sessionId, []);
  }
  await refreshActiveSession(false);
}

async function refreshActiveSession(showNotice) {
  if (!state.activeSessionId) {
    render();
    return;
  }
  try {
    const { data } = await apiRequest(`/api/v0/sessions/${state.activeSessionId}`);
    const index = state.sessions.findIndex((item) => item.session_id === state.activeSessionId);
    if (index >= 0) {
      state.sessions[index] = data;
    } else {
      state.sessions.unshift(data);
    }
    state.sessions.sort(compareByUpdatedAtDesc);

    if (data.current_requirement_doc && data.current_requirement_doc.content) {
      const messages = state.messagesBySession.get(state.activeSessionId) || [];
      const alreadyInjected = messages.some((msg) => msg.kind === "snapshot-doc");
      if (!alreadyInjected) {
        addMessage(
          state.activeSessionId,
          "assistant",
          `当前会话已有需求文档快照:\n${data.current_requirement_doc.content}`,
          "snapshot-doc"
        );
      }
    }

    await refreshTickets(false);
    await refreshWorkersFromBackend(false);
    if (showNotice) {
      showToast("会话信息已刷新");
    }
  } catch (error) {
    showToast(String(error.message || error), true);
  } finally {
    render();
  }
}

async function sendChatMessage() {
  const text = refs.messageInput.value.trim();
  if (!text) {
    return;
  }
  if (!state.activeSessionId) {
    showToast("请先创建会话", true);
    return;
  }

  refs.sendBtn.disabled = true;
  refs.messageInput.disabled = true;
  addMessage(state.activeSessionId, "user", text);
  refs.messageInput.value = "";
  renderChat();

  try {
    const activeSession = getActiveSession();
    const { data } = await apiRequest(
      `/api/v0/sessions/${state.activeSessionId}/requirement-agent/drafts`,
      {
        method: "POST",
        body: {
          title: activeSession?.title || `Session ${state.activeSessionId}`,
          user_input: text,
          persist: true,
        },
      }
    );

    const parts = [];
    if (data.phase) {
      parts.push(`[${data.phase}]`);
    }
    if (data.assistant_message) {
      parts.push(data.assistant_message);
    }
    if (Array.isArray(data.missing_information) && data.missing_information.length > 0) {
      parts.push(`缺失信息:\n- ${data.missing_information.join("\n- ")}`);
    }
    if (data.content) {
      parts.push(data.content);
    }
    const textOut = parts.length > 0 ? parts.join("\n\n") : "已接收请求。";
    addMessage(state.activeSessionId, "assistant", textOut);
    await refreshActiveSession(false);
  } catch (error) {
    addMessage(state.activeSessionId, "system", `请求失败: ${error.message}`);
    showToast(String(error.message || error), true);
  } finally {
    refs.sendBtn.disabled = false;
    refs.messageInput.disabled = false;
    refs.messageInput.focus();
    render();
  }
}

function addMessage(sessionId, role, text, kind = "") {
  if (!state.messagesBySession.has(sessionId)) {
    state.messagesBySession.set(sessionId, []);
  }
  const list = state.messagesBySession.get(sessionId);
  list.push({
    role,
    text,
    kind,
    at: new Date().toISOString(),
  });
}

async function confirmRequirement(silent) {
  if (!state.activeSessionId) {
    if (!silent) {
      showToast("当前没有可确认的会话", true);
    }
    return false;
  }
  try {
    const { data: session } = await apiRequest(`/api/v0/sessions/${state.activeSessionId}`);
    const doc = session.current_requirement_doc;
    if (!doc || !doc.doc_id) {
      if (!silent) {
        showToast("还没有生成需求文档，先在中间对话区沟通需求", true);
      }
      return false;
    }
    if (doc.confirmed_version) {
      if (!silent) {
        showToast("需求已确认，无需重复确认");
      }
      return true;
    }
    const { data: confirmed } = await apiRequest(`/api/v0/requirement-docs/${doc.doc_id}/confirm`, {
      method: "POST",
    });
    addMessage(state.activeSessionId, "system", `需求已确认: confirmed_version=${confirmed.confirmed_version}`);
    await refreshActiveSession(false);
    return true;
  } catch (error) {
    if (!silent) {
      showToast(String(error.message || error), true);
    }
    return false;
  }
}

async function refreshTickets(showNotice) {
  if (!state.activeSessionId) {
    state.tickets = [];
    render();
    return;
  }
  try {
    const { data } = await apiRequest(`/api/v0/sessions/${state.activeSessionId}/tickets`, {
      query: { assignee_role: "architect_agent" },
    });
    state.tickets = Array.isArray(data) ? data.slice().sort(compareByUpdatedAtDesc) : [];
    await preloadActionableTicketEvents(state.tickets);
    if (showNotice) {
      showToast("提请列表已刷新");
    }
  } catch (error) {
    if (showNotice) {
      showToast(String(error.message || error), true);
    }
  } finally {
    render();
  }
}

async function preloadActionableTicketEvents(tickets) {
  const actionable = tickets
    .filter((ticket) => ticket.status === "WAITING_USER")
    .map((ticket) => ticket.ticket_id);
  if (actionable.length === 0) {
    return;
  }
  const jobs = actionable.map(async (ticketId) => {
    const { data } = await apiRequest(`/api/v0/tickets/${ticketId}/events`);
    state.ticketEventsById.set(ticketId, Array.isArray(data) ? data : []);
  });
  await Promise.allSettled(jobs);
}

async function loadTicketEvents(ticketId, silent = false) {
  try {
    const { data } = await apiRequest(`/api/v0/tickets/${ticketId}/events`);
    state.ticketEventsById.set(ticketId, Array.isArray(data) ? data : []);
    renderTickets();
    renderProgress();
  } catch (error) {
    if (!silent) {
      showToast(String(error.message || error), true);
    }
  }
}

async function submitTicketResponse(ticket, request, formElement) {
  const requestKind = normalizeRequestKind(request?.requestKind || request?.request_kind || "CLARIFICATION");
  const selectedRadio = formElement.querySelector("input[type='radio']:checked");
  const selectedOptionId = selectedRadio ? selectedRadio.value.trim() : "";
  const customOptionInput = formElement.querySelector("[data-field='custom-option']");
  const responseTextInput = formElement.querySelector("[data-field='response-text']");
  const noteInput = formElement.querySelector("[data-field='note']");
  const customOption = customOptionInput ? customOptionInput.value.trim() : "";
  const responseText = responseTextInput ? responseTextInput.value.trim() : "";
  const note = noteInput ? noteInput.value.trim() : "";

  if (requestKind === "DECISION" && !selectedOptionId && !customOption && !responseText) {
    showToast("请先选择方案，或填写自定义方案/补充说明。", true);
    return;
  }
  if (requestKind === "CLARIFICATION" && !responseText && !customOption) {
    showToast("请填写澄清回复，或填写自定义处理方案。", true);
    return;
  }

  const bodyLines = [];
  if (requestKind === "DECISION") {
    if (selectedOptionId) {
      bodyLines.push(`选择方案: ${selectedOptionId}`);
    }
    if (customOption) {
      bodyLines.push(`自定义方案: ${customOption}`);
    }
    if (responseText) {
      bodyLines.push(`说明: ${responseText}`);
    }
    if (note) {
      bodyLines.push(`备注: ${note}`);
    }
  } else {
    if (responseText) {
      bodyLines.push(responseText);
    }
    if (customOption) {
      bodyLines.push(`补充方案: ${customOption}`);
    }
    if (note) {
      bodyLines.push(`备注: ${note}`);
    }
  }

  const dataJson = {
    request_kind: requestKind,
    selected_option_id: selectedOptionId || null,
    selected: selectedOptionId || customOption || null,
    custom_option: customOption || null,
    response_text: responseText || null,
    note: note || null,
    source_ticket_id: ticket.ticket_id,
  };

  try {
    await apiRequest(`/api/v0/tickets/${ticket.ticket_id}/events`, {
      method: "POST",
      body: {
        event_type: "USER_RESPONDED",
        actor_role: "user",
        body: bodyLines.join("\n") || responseText || customOption || "用户已提供反馈",
        data_json: JSON.stringify(compactObject(dataJson)),
      },
    });
    state.ticketDraftsById.delete(ticket.ticket_id);
    state.openTicketIds.delete(ticket.ticket_id);
    await apiRequest("/api/v0/architect/auto-process", {
      method: "POST",
      body: {
        session_id: state.activeSessionId,
        max_tickets: 8,
      },
    });
    await refreshTickets(false);
    await loadTicketEvents(ticket.ticket_id, true);
    addMessage(state.activeSessionId, "system", `已对提请 ${ticket.ticket_id} 回应，架构师继续处理中。`);
    showToast(`已提交 ${ticket.ticket_id} 的回应`);
  } catch (error) {
    showToast(String(error.message || error), true);
  }
}

async function triggerArchitectAutoProcess() {
  if (!state.activeSessionId) {
    showToast("请先创建会话", true);
    return null;
  }
  const { data } = await apiRequest("/api/v0/architect/auto-process", {
    method: "POST",
    body: {
      session_id: state.activeSessionId,
      max_tickets: 8,
    },
  });
  await refreshTickets(false);
  return data;
}

async function refreshWorkersFromBackend(showNotice = false) {
  try {
    const { data } = await apiRequest("/api/v0/workforce/workers", {
      query: {
        status: "READY,PROVISIONING,DISABLED",
        limit: 512,
      },
    });
    const rows = Array.isArray(data?.workers) ? data.workers : [];
    const nextWorkers = new Map();
    rows.forEach((item) => {
      const workerId = toText(item.worker_id);
      if (!workerId) {
        return;
      }
      const previous = state.workersById.get(workerId);
      nextWorkers.set(workerId, {
        workerId,
        status: toText(item.status || "UNKNOWN"),
        createdAt: item.created_at || previous?.createdAt || new Date().toISOString(),
        updatedAt: item.updated_at || previous?.updatedAt || new Date().toISOString(),
        toolpackIds: Array.isArray(item.toolpack_ids) ? item.toolpack_ids.map((v) => toText(v)).filter(Boolean) : [],
        lastClaim: previous?.lastClaim || null,
        lastRunSummary: previous?.lastRunSummary || null,
      });
    });
    state.workersById = nextWorkers;
    if (showNotice) {
      showToast(`Worker 列表已刷新，可用 READY: ${countWorkersByStatus("READY")}`);
    }
  } catch (error) {
    if (showNotice) {
      showToast(String(error.message || error), true);
    }
  }
}

function countWorkersByStatus(status) {
  const normalized = toText(status).toUpperCase();
  return Array.from(state.workersById.values()).filter((worker) => toText(worker.status).toUpperCase() === normalized)
    .length;
}

async function triggerAutoProvision() {
  try {
    const { data } = await apiRequest("/api/v0/workforce/auto-provision", {
      method: "POST",
      body: { max_tasks: 64 },
    });
    await refreshWorkersFromBackend(false);
    const createdCount = Number(data?.created_workers || 0);
    showToast(`自动分配完成，新增 worker: ${createdCount}，当前 READY: ${countWorkersByStatus("READY")}`);
    renderWorkers();
    renderProgress();
  } catch (error) {
    showToast(String(error.message || error), true);
  }
}

async function triggerAutoRun() {
  try {
    const { data } = await apiRequest("/api/v0/workforce/runtime/auto-run", {
      method: "POST",
      body: { max_workers: 8 },
    });
    state.runtimeSummary = {
      claimedRuns: data.claimed_runs || 0,
      succeededRuns: data.succeeded_runs || 0,
      needInputRuns: data.need_input_runs || 0,
      failedRuns: data.failed_runs || 0,
    };
    const workerList = Array.from(state.workersById.values());
    workerList.forEach((worker) => {
      worker.lastRunSummary = { ...state.runtimeSummary, at: new Date().toISOString() };
    });
    await refreshWorkersFromBackend(false);
    if (state.runtimeSummary.succeededRuns > 0) {
      void openGitModalWithPublication();
    }
    const readyCount = countWorkersByStatus("READY");
    const claimed = state.runtimeSummary.claimedRuns;
    const reasonHint = claimed === 0
      ? readyCount > 0
        ? "，READY worker 已就绪，当前可能无可认领任务或被 gate 阻塞"
        : "，当前没有 READY worker"
      : "";
    showToast(
      `执行轮询完成: claimed=${claimed}, succeeded=${state.runtimeSummary.succeededRuns}${reasonHint}`
    );
    renderWorkers();
    renderProgress();
  } catch (error) {
    showToast(String(error.message || error), true);
  }
}

async function claimWorkerTask(workerId) {
  try {
    const { status, data } = await apiRequest(`/api/v0/workers/${workerId}/claim`, {
      method: "POST",
    });
    const worker = state.workersById.get(workerId);
    if (!worker) {
      return;
    }
    if (status === 204 || !data) {
      worker.lastClaim = {
        at: new Date().toISOString(),
        note: "当前无可认领任务",
      };
      showToast(`worker ${workerId} 当前无可认领任务`);
      renderWorkers();
      return;
    }
    worker.lastClaim = {
      at: new Date().toISOString(),
      note: "已认领任务",
      package: data,
    };
    showToast(`worker ${workerId} 已认领任务 ${data.task_id}`);
    renderWorkers();
    renderProgress();
  } catch (error) {
    showToast(String(error.message || error), true);
  }
}

async function advanceOneStep() {
  if (!state.activeSessionId) {
    showToast("请先创建会话", true);
    return;
  }
  refs.advanceBtn.disabled = true;
  try {
    const confirmed = await confirmRequirement(true);
    if (!confirmed) {
      showToast("先在中间对话区生成并确认需求文档");
      return;
    }

    await triggerArchitectAutoProcess();
    await refreshTickets(false);
    const waitingTicket = state.tickets.find((item) => item.status === "WAITING_USER");
    if (waitingTicket) {
      await loadTicketEvents(waitingTicket.ticket_id);
      showToast("发现待处理架构提请，请在右侧提交你的决策或澄清。");
      return;
    }

    await triggerAutoProvision();
    await triggerAutoRun();
    await refreshActiveSession(false);
  } catch (error) {
    showToast(String(error.message || error), true);
  } finally {
    refs.advanceBtn.disabled = false;
  }
}

function render() {
  renderSessions();
  renderChat();
  renderTickets();
  renderProgress();
  renderWorkers();
}

function renderSessions() {
  refs.sessionList.innerHTML = "";
  const total = state.sessions.length;
  const visibleSessions = state.showAllSessions
    ? state.sessions
    : state.sessions.slice(0, SESSION_PREVIEW_LIMIT);
  refs.toggleSessionBtn.classList.toggle("hidden", total <= SESSION_PREVIEW_LIMIT);
  refs.toggleSessionBtn.textContent = state.showAllSessions ? "收起" : "显示全部";
  refs.sessionCount.textContent =
    total > SESSION_PREVIEW_LIMIT
      ? `共 ${total} 个会话，当前显示 ${visibleSessions.length} 个`
      : `共 ${total} 个会话`;

  visibleSessions.forEach((session) => {
    const li = document.createElement("li");
    li.className = `session-item ${session.session_id === state.activeSessionId ? "active" : ""}`;
    li.innerHTML = `
      <p class="session-title">${escapeHtml(session.title || session.session_id)}</p>
      <p class="session-meta">${escapeHtml(session.status || "-")} · ${formatDateTime(session.updated_at || session.created_at)}</p>
    `;
    li.addEventListener("click", () => void selectSession(session.session_id));
    refs.sessionList.appendChild(li);
  });
}

function renderChat() {
  const session = getActiveSession();
  refs.activeSessionTitle.textContent = session ? session.title || session.session_id : "未选择会话";
  const doc = session?.current_requirement_doc;
  const docStatus = doc ? ` · 文档 ${doc.status} v${doc.current_version}` : "";
  refs.activeSessionMeta.textContent = session ? `状态: ${session.status}${docStatus}` : "状态: -";
  renderChatFeed();
}

function renderChatFeed() {
  refs.chatFeed.innerHTML = "";
  if (!state.activeSessionId) {
    refs.chatFeed.innerHTML = `<div class="bubble system"><div class="bubble-head">系统</div>请先创建会话。</div>`;
    return;
  }
  const messages = state.messagesBySession.get(state.activeSessionId) || [];
  if (messages.length === 0) {
    refs.chatFeed.innerHTML = `<div class="bubble system"><div class="bubble-head">系统</div>会话已准备好，输入需求开始对话。</div>`;
  } else {
    messages.forEach((msg) => {
      const div = document.createElement("div");
      div.className = `bubble ${msg.role}`;
      const roleName = msg.role === "user" ? "你" : msg.role === "assistant" ? "AgentX" : "系统";
      div.innerHTML = `
        <div class="bubble-head">${roleName} · ${formatDateTime(msg.at)}</div>
        ${escapeHtml(msg.text)}
      `.replace(/\n/g, "<br>");
      refs.chatFeed.appendChild(div);
    });
  }
  refs.chatFeed.scrollTop = refs.chatFeed.scrollHeight;
}

function renderTickets() {
  refs.ticketList.innerHTML = "";
  if (!state.activeSessionId) {
    refs.ticketList.innerHTML = `<p class="muted">请先创建会话。</p>`;
    return;
  }
  const actionable = state.tickets.filter((ticket) => ticket.status === "WAITING_USER");
  if (actionable.length === 0) {
    syncTicketClientState([]);
    refs.ticketList.innerHTML = `<p class="muted">当前没有需要你决策/澄清的提请。点击“推进一步”后会自动触发下一轮。</p>`;
    return;
  }
  syncTicketClientState(actionable.map((ticket) => ticket.ticket_id));
  actionable.forEach((ticket) => {
    refs.ticketList.appendChild(buildTicketCard(ticket));
  });
}

function buildTicketCard(ticket) {
  const request = buildTicketRequestViewModel(ticket);
  const requestKind = normalizeRequestKind(request.requestKind || "CLARIFICATION");
  const kindLabel = requestKind === "DECISION" ? "决策请求" : "澄清请求";
  const questionText = request.question || ticket.title || "请查看并回复该提请。";
  const optionsHtml = requestKind === "DECISION"
    ? renderDecisionOptions(ticket.ticket_id, request.options, request.recommendationOptionId)
    : "";
  const contextHtml = request.context.length > 0
    ? `<ul class="request-context">${request.context.map((line) => `<li>${escapeHtml(line)}</li>`).join("")}</ul>`
    : `<p class="muted">暂无补充上下文。</p>`;
  const requestDetailHtml = request.hasDecisionEvent
    ? `
      <div class="request-block">
        <div class="request-question">${escapeHtml(questionText)}</div>
        <div class="request-meta">类型: ${escapeHtml(kindLabel)} · ticket: ${escapeHtml(ticket.ticket_id)}</div>
      </div>
      <div class="request-block">
        <div class="input-label">上下文</div>
        ${contextHtml}
      </div>
      ${request.recommendationText
      ? `<p class="muted">架构建议: ${escapeHtml(request.recommendationText)}</p>`
      : ""}
    `
    : `
      <p class="muted">提请详情尚未加载，请点击下方“刷新详情”。</p>
    `;

  const details = document.createElement("details");
  details.className = "card-item";
  const events = request.events;
  const eventTypeSummary = events.slice(-4).map((event) => event.event_type).join(" -> ");
  details.innerHTML = `
    <summary>
      <span class="card-title">${escapeHtml(ticket.title || ticket.ticket_id)}</span>
      <span class="badge">${escapeHtml(kindLabel)}</span>
    </summary>
    <div class="card-body">
      <div class="request-block">
        <div><strong>更新时间:</strong> ${formatDateTime(ticket.updated_at || ticket.created_at)}</div>
        <div><strong>最近事件:</strong> ${escapeHtml(eventTypeSummary || "未加载")}</div>
      </div>
      ${requestDetailHtml}
      <form class="ticket-response-form" data-ticket-id="${escapeHtml(ticket.ticket_id)}" data-request-kind="${escapeHtml(requestKind)}">
        ${requestKind === "DECISION"
      ? `
        <div class="request-block">
          <div class="input-label">候选方案（可不选，直接填写自定义）</div>
          ${optionsHtml}
        </div>`
      : ""}
        <label class="input-label" for="response-${escapeHtml(ticket.ticket_id)}">
          ${requestKind === "DECISION" ? "你的决策说明（可选）" : "你的澄清回复"}
        </label>
        <textarea
          id="response-${escapeHtml(ticket.ticket_id)}"
          class="ticket-textarea"
          data-field="response-text"
          placeholder="${requestKind === "DECISION" ? "填写选择理由、约束、边界等（可不填）" : "请直接补充缺失信息，便于架构师继续推进"}"
        ></textarea>
        <label class="input-label">自定义${requestKind === "DECISION" ? "方案" : "处理建议"}（可选）</label>
        <input
          class="text-input"
          type="text"
          data-field="custom-option"
          placeholder="${requestKind === "DECISION" ? "例如：OPT-C：分阶段改造" : "例如：先补充数据库约束，再继续拆解"}"
        >
        <label class="input-label">备注（可选）</label>
        <textarea class="ticket-textarea ticket-textarea-small" data-field="note" placeholder="可填写时间、风险偏好、预算边界等"></textarea>
        <div class="chat-actions ticket-actions">
          <button type="button" class="ghost-btn small-btn" data-action="refresh-events" data-ticket-id="${escapeHtml(ticket.ticket_id)}">刷新详情</button>
          <button type="submit" class="primary-btn small-btn">提交回应</button>
        </div>
      </form>
      <details class="inline-details">
        <summary class="muted">查看原始事件/载荷</summary>
        <div class="request-block">
          <div><strong>payload:</strong></div>
          <pre>${escapeHtml(formatJson(ticket.payload_json))}</pre>
          ${events.length > 0 ? `<pre>${escapeHtml(renderEventLines(events))}</pre>` : `<p class="muted">暂无事件详情</p>`}
        </div>
      </details>
    </div>
  `;
  details.open = state.openTicketIds.has(ticket.ticket_id);

  details.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) {
      return;
    }
    const action = target.getAttribute("data-action");
    const ticketId = target.getAttribute("data-ticket-id");
    if (action === "refresh-events" && ticketId) {
      event.preventDefault();
      void loadTicketEvents(ticketId);
    }
  });
  details.addEventListener("toggle", () => {
    if (details.open) {
      state.openTicketIds.add(ticket.ticket_id);
    } else {
      state.openTicketIds.delete(ticket.ticket_id);
    }
  });

  const formElement = details.querySelector("form.ticket-response-form");
  if (formElement) {
    restoreTicketDraft(ticket.ticket_id, formElement);
    const draftEvents = ["input", "change"];
    draftEvents.forEach((eventName) => {
      formElement.addEventListener(eventName, () => {
        saveTicketDraft(ticket.ticket_id, formElement);
      });
    });
    formElement.addEventListener("submit", (event) => {
      event.preventDefault();
      void submitTicketResponse(ticket, request, formElement);
    });
  }
  return details;
}

function buildTicketRequestViewModel(ticket) {
  const events = state.ticketEventsById.get(ticket.ticket_id) || [];
  let decisionEvent = null;
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (events[index].event_type === "DECISION_REQUESTED") {
      decisionEvent = events[index];
      break;
    }
  }
  if (!decisionEvent) {
    return {
      hasDecisionEvent: false,
      requestKind: "CLARIFICATION",
      question: "",
      options: [],
      recommendationOptionId: "",
      recommendationText: "",
      context: [],
      events,
    };
  }

  const data = parseJsonObject(decisionEvent.data_json);
  const options = parseDecisionOptions(data.options);
  const requestKind = normalizeRequestKind(data.request_kind || (options.length > 0 ? "DECISION" : "CLARIFICATION"));
  const recommendationOptionId = toText(
    data.recommendation?.option_id || data.recommendation?.optionId || data.recommendation?.id
  );
  const recommendationReason = toText(data.recommendation?.reason);
  const recommendationText = recommendationOptionId
    ? recommendationReason
      ? `${recommendationOptionId} - ${recommendationReason}`
      : recommendationOptionId
    : recommendationReason;

  const context = [];
  if (Array.isArray(data.context)) {
    data.context.forEach((line) => {
      const text = toText(line);
      if (text) {
        context.push(text);
      }
    });
  }
  if (requestKind === "CLARIFICATION" && Array.isArray(data.missing_toolpacks) && data.missing_toolpacks.length > 0) {
    context.push(`缺失工具包: ${data.missing_toolpacks.map((item) => toText(item)).filter(Boolean).join(", ")}`);
  }

  return {
    hasDecisionEvent: true,
    requestKind,
    question: toText(data.question || decisionEvent.body || ticket.title),
    options,
    recommendationOptionId,
    recommendationText,
    context,
    events,
  };
}

function renderDecisionOptions(ticketId, options, recommendationOptionId) {
  if (!Array.isArray(options) || options.length === 0) {
    return `<p class="muted">当前没有预设方案，请直接填写“自定义方案”后提交。</p>`;
  }
  return `<div class="request-options">
    ${options.map((option, index) => renderDecisionOptionCard(ticketId, option, index, recommendationOptionId)).join("")}
  </div>`;
}

function renderDecisionOptionCard(ticketId, option, index, recommendationOptionId) {
  const optionId = option.optionId || `OPTION_${index + 1}`;
  const optionTitle = option.title || optionId;
  const tag = recommendationOptionId && recommendationOptionId === optionId
    ? `<span class="badge">推荐</span>`
    : "";
  return `
    <label class="option-card">
      <input type="radio" name="decision-option-${escapeHtml(ticketId)}" value="${escapeHtml(optionId)}">
      <div class="option-content">
        <div class="option-title-row">
          <span class="card-title">${escapeHtml(optionTitle)}</span>
          ${tag}
        </div>
        <div class="muted option-id">${escapeHtml(optionId)}</div>
        ${renderOptionList("优点", option.pros)}
        ${renderOptionList("代价", option.costNotes)}
        ${renderOptionList("风险", option.risks)}
        ${renderOptionList("不足", option.cons)}
      </div>
    </label>
  `;
}

function renderOptionList(label, values) {
  if (!Array.isArray(values) || values.length === 0) {
    return "";
  }
  return `<div class="option-list"><strong>${escapeHtml(label)}:</strong> ${values.map((item) => escapeHtml(item)).join("；")}</div>`;
}

function parseDecisionOptions(rawOptions) {
  if (!Array.isArray(rawOptions)) {
    return [];
  }
  return rawOptions
    .map((item, index) => {
      if (typeof item === "string") {
        const text = item.trim();
        if (!text) {
          return null;
        }
        return {
          optionId: text,
          title: text,
          pros: [],
          cons: [],
          risks: [],
          costNotes: [],
        };
      }
      if (!item || typeof item !== "object") {
        return null;
      }
      const optionId = toText(item.option_id || item.optionId || item.id || item.key || `OPTION_${index + 1}`);
      const title = toText(item.title || item.label || item.name || optionId);
      return {
        optionId,
        title,
        pros: toStringList(item.pros),
        cons: toStringList(item.cons),
        risks: toStringList(item.risks),
        costNotes: toStringList(item.cost_notes || item.costNotes),
      };
    })
    .filter(Boolean);
}

function parseJsonObject(value) {
  if (!value || typeof value !== "string") {
    return {};
  }
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function toStringList(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map((item) => toText(item)).filter(Boolean);
}

function toText(value) {
  if (value === undefined || value === null) {
    return "";
  }
  const text = String(value).trim();
  return text;
}

function normalizeRequestKind(value) {
  const normalized = String(value || "").trim().toUpperCase();
  if (normalized === "DECISION") {
    return "DECISION";
  }
  if (normalized === "CLARIFICATION") {
    return "CLARIFICATION";
  }
  return "CLARIFICATION";
}

function compactObject(value) {
  const result = {};
  Object.entries(value || {}).forEach(([key, item]) => {
    if (item !== undefined && item !== null && item !== "") {
      result[key] = item;
    }
  });
  return result;
}

function syncTicketClientState(activeTicketIds) {
  const activeSet = new Set(activeTicketIds || []);
  Array.from(state.openTicketIds).forEach((ticketId) => {
    if (!activeSet.has(ticketId)) {
      state.openTicketIds.delete(ticketId);
    }
  });
  Array.from(state.ticketDraftsById.keys()).forEach((ticketId) => {
    if (!activeSet.has(ticketId)) {
      state.ticketDraftsById.delete(ticketId);
    }
  });
}

function saveTicketDraft(ticketId, formElement) {
  const selectedRadio = formElement.querySelector("input[type='radio']:checked");
  const customOptionInput = formElement.querySelector("[data-field='custom-option']");
  const responseTextInput = formElement.querySelector("[data-field='response-text']");
  const noteInput = formElement.querySelector("[data-field='note']");

  state.ticketDraftsById.set(ticketId, {
    selectedOptionId: selectedRadio ? selectedRadio.value : "",
    customOption: customOptionInput ? customOptionInput.value : "",
    responseText: responseTextInput ? responseTextInput.value : "",
    note: noteInput ? noteInput.value : "",
  });
}

function restoreTicketDraft(ticketId, formElement) {
  const draft = state.ticketDraftsById.get(ticketId);
  if (!draft) {
    return;
  }
  const radios = Array.from(formElement.querySelectorAll("input[type='radio']"));
  radios.forEach((radio) => {
    radio.checked = Boolean(draft.selectedOptionId && radio.value === draft.selectedOptionId);
  });
  const customOptionInput = formElement.querySelector("[data-field='custom-option']");
  const responseTextInput = formElement.querySelector("[data-field='response-text']");
  const noteInput = formElement.querySelector("[data-field='note']");
  if (customOptionInput) {
    customOptionInput.value = draft.customOption || "";
  }
  if (responseTextInput) {
    responseTextInput.value = draft.responseText || "";
  }
  if (noteInput) {
    noteInput.value = draft.note || "";
  }
}

function handleLanguageChange() {
  const selected = normalizeLanguageCode(refs.languageSelect.value);
  state.uiLanguage = selected;
  refs.languageSelect.value = selected;
  localStorage.setItem("agentx_ui_language", selected);
  showToast(`语言已切换为 ${languageLabel(selected)}，后续请求将按该语言生成`);
}

function languageLabel(languageCode) {
  if (languageCode === "zh-CN") {
    return "中文";
  }
  if (languageCode === "en-US") {
    return "English";
  }
  if (languageCode === "ja-JP") {
    return "日本語";
  }
  return languageCode;
}

function normalizeLanguageCode(value) {
  const normalized = String(value || "").trim();
  if (normalized === "en-US") {
    return "en-US";
  }
  if (normalized === "ja-JP") {
    return "ja-JP";
  }
  return "zh-CN";
}

function renderWorkers() {
  refs.workerList.innerHTML = "";
  const workers = Array.from(state.workersById.values());
  if (workers.length === 0) {
    refs.workerList.innerHTML = `<p class="muted">后端当前未返回 worker。可点击“自动分配”或稍后刷新。</p>`;
    return;
  }
  workers.forEach((worker) => {
    const backendStatus = toText(worker.status || "UNKNOWN");
    const canClaim = backendStatus === "READY";
    const toolpacks = Array.isArray(worker.toolpackIds) ? worker.toolpackIds : [];
    const details = document.createElement("details");
    details.className = "card-item";
    details.innerHTML = `
      <summary>
        <span class="card-title">${escapeHtml(worker.workerId)}</span>
        <span class="badge">${escapeHtml(backendStatus)}</span>
      </summary>
      <div class="card-body">
        <div><strong>后端状态:</strong> ${escapeHtml(backendStatus)}</div>
        <div><strong>工具包:</strong> ${toolpacks.length > 0 ? escapeHtml(toolpacks.join(", ")) : "-"}</div>
        <div><strong>最近认领:</strong> ${worker.lastClaim ? formatDateTime(worker.lastClaim.at) : "-"}</div>
        <div><strong>任务状态:</strong> ${escapeHtml(worker.lastClaim?.note || "未拉取任务")}</div>
        <div><strong>后端更新时间:</strong> ${formatDateTime(worker.updatedAt || worker.createdAt)}</div>
        <div class="chat-actions" style="margin-top:8px;">
          <button class="ghost-btn small-btn" data-action="claim-task" data-worker-id="${escapeHtml(worker.workerId)}" ${canClaim ? "" : "disabled"}>
            ${canClaim ? "拉取任务与上下文" : "仅 READY 可拉取任务"}
          </button>
        </div>
        ${
          worker.lastClaim?.package
            ? `<pre>${escapeHtml(formatJson(worker.lastClaim.package))}</pre>`
            : ""
        }
      </div>
    `;
    details.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) {
        return;
      }
      const action = target.getAttribute("data-action");
      const workerId = target.getAttribute("data-worker-id");
      if (action === "claim-task" && workerId) {
        event.preventDefault();
        void claimWorkerTask(workerId);
      }
    });
    refs.workerList.appendChild(details);
  });
}

function renderProgress() {
  refs.progressList.innerHTML = "";
  const session = getActiveSession();
  const doc = session?.current_requirement_doc;
  const hasMessages = (state.messagesBySession.get(state.activeSessionId || "") || []).length > 0;
  const hasArchTickets = state.tickets.length > 0;
  const waitingUser = state.tickets.some((ticket) => ticket.status === "WAITING_USER");
  const archDone = state.tickets.some((ticket) => ticket.status === "DONE");
  const hasWorkers = countWorkersByStatus("READY") > 0 || state.workersById.size > 0;
  const codeReady = state.runtimeSummary.succeededRuns > 0;

  const steps = [
    { label: "会话已创建", done: Boolean(session) },
    { label: "需求沟通进行中", done: hasMessages },
    { label: "需求文档已确认", done: Boolean(doc?.confirmed_version) },
    { label: "架构师提请已生成", done: hasArchTickets },
    { label: "架构拆解已完成", done: archDone && !waitingUser },
    { label: "Worker 已介入", done: hasWorkers },
    { label: "代码产出可拉取", done: codeReady },
  ];

  steps.forEach((step) => {
    const li = document.createElement("li");
    li.className = `progress-step ${step.done ? "done" : ""}`;
    li.textContent = `${step.done ? "✓" : "·"} ${step.label}`;
    refs.progressList.appendChild(li);
  });

  if (codeReady && !state.modalShown) {
    void openGitModalWithPublication();
  }
}

function renderEventLines(events) {
  return events
    .slice()
    .reverse()
    .slice(0, 12)
    .map((event) => {
      const line = `[${formatDateTime(event.created_at)}] ${event.event_type} (${event.actor_role})`;
      const body = event.body ? `  ${event.body}` : "";
      return `${line}${body ? `\n${body}` : ""}`;
    })
    .join("\n");
}

function persistGitAddress() {
  const value = refs.gitAddressInput.value.trim() || DEFAULT_GIT_URL;
  localStorage.setItem("agentx_git_url", value);
  syncCloneDisplay(value);
  refs.gitCloneMeta.textContent = "当前为手动地址（自动发布地址不可用时使用）。";
  showToast("Git 地址已更新");
}

function openGitModal() {
  refs.gitModal.classList.remove("hidden");
  state.modalShown = true;
}

async function openGitModalWithPublication() {
  const sessionId = state.activeSessionId;
  if (sessionId) {
    try {
      let publication = state.clonePublicationBySession.get(sessionId);
      if (!isPublicationActive(publication)) {
        publication = await publishDeliveryCloneRepo(sessionId);
        if (publication) {
          state.clonePublicationBySession.set(sessionId, publication);
        }
      }
      if (publication) {
        applyDeliveryClonePublication(publication);
      } else {
        refs.gitCloneMeta.textContent = "暂未生成自动克隆地址，已回退到手动地址。";
      }
    } catch (error) {
      refs.gitCloneMeta.textContent = "自动生成克隆地址失败，已回退到手动地址。";
      showToast(String(error.message || error), true);
    }
  }
  openGitModal();
}

async function publishDeliveryCloneRepo(sessionId) {
  const { data } = await apiRequest(`/api/v0/sessions/${sessionId}/delivery/clone-repo`, {
    method: "POST",
  });
  return data || null;
}

function applyDeliveryClonePublication(publication) {
  if (!publication || !publication.clone_url) {
    return;
  }
  refs.gitAddressInput.value = publication.clone_url;
  localStorage.setItem("agentx_git_url", publication.clone_url);
  syncCloneDisplay(publication.clone_url);
  const expiresAt = publication.expires_at ? formatDateTime(publication.expires_at) : "-";
  refs.gitCloneMeta.textContent = `临时仓库有效期至: ${expiresAt}`;
}

function isPublicationActive(publication) {
  if (!publication || !publication.expires_at) {
    return false;
  }
  const expiresAtMs = Date.parse(publication.expires_at);
  if (Number.isNaN(expiresAtMs)) {
    return false;
  }
  return expiresAtMs > Date.now();
}

function syncCloneDisplay(value) {
  const cloneUrl = (value || "").trim() || DEFAULT_GIT_URL;
  refs.gitAddress.textContent = cloneUrl;
  refs.gitCloneCommand.textContent = `git clone ${cloneUrl}`;
}

function closeGitModal() {
  refs.gitModal.classList.add("hidden");
}

async function copyGitUrl() {
  try {
    await navigator.clipboard.writeText(refs.gitAddress.textContent);
    showToast("Git 地址已复制");
  } catch {
    showToast("复制失败，请手动复制", true);
  }
}

function showToast(message, isError = false) {
  refs.toast.textContent = message;
  refs.toast.classList.remove("hidden");
  refs.toast.classList.toggle("error", isError);
  setTimeout(() => refs.toast.classList.add("hidden"), 3200);
}

function formatDateTime(value) {
  if (!value) {
    return "-";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

function formatJson(value) {
  if (value === null || value === undefined || value === "") {
    return "-";
  }
  if (typeof value === "string") {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

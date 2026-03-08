import { useEffect, useState, useTransition } from "react";
import {
  AUTO_REFRESH_MS,
  apiRequest,
  arrayOf,
  buildFallbackProgress,
  buildRequirementTemplate,
  buildRuntimeEditor,
  buildRuntimeRequest,
  buildTicketInboxFallback,
  findTicketById,
  flattenTasks,
  getDocId,
  getRequirementDoc,
  getSessionId,
  getSessionTitle,
  getTicketId,
  inferRequestKind,
  read,
} from "./controlPlane";
import { readPreferredLocale, translate, translateServerValue } from "./i18n";

export function useMissionRoom() {
  const initialNav = readNavigationState();
  const [sessions, setSessions] = useState([]);
  const [workspaceMode, setWorkspaceMode] = useState(initialNav.workspaceMode);
  const [projectView, setProjectView] = useState(initialNav.projectView);
  const [opsView, setOpsView] = useState(initialNav.opsView);
  const [executionView, setExecutionView] = useState(initialNav.executionView);
  const [activeSessionId, setActiveSessionId] = useState(initialNav.activeSessionId);

  const [sessionDetails, setSessionDetails] = useState({});
  const [progressBySession, setProgressBySession] = useState({});
  const [ticketInboxBySession, setTicketInboxBySession] = useState({});
  const [taskBoardBySession, setTaskBoardBySession] = useState({});
  const [runTimelineBySession, setRunTimelineBySession] = useState({});
  const [cloneRepoBySession, setCloneRepoBySession] = useState({});
  const [ticketEventsById, setTicketEventsById] = useState({});

  const [requirementEditors, setRequirementEditors] = useState({});
  const [ticketReplies, setTicketReplies] = useState({});
  const [selectedTicketBySession, setSelectedTicketBySession] = useState({});
  const [selectedTaskBySession, setSelectedTaskBySession] = useState({});
  const [selectedRunBySession, setSelectedRunBySession] = useState({});

  const [runtimeConfig, setRuntimeConfig] = useState(null);
  const [runtimeEditor, setRuntimeEditor] = useState(null);
  const [runtimeProbe, setRuntimeProbe] = useState(null);
  const [workers, setWorkers] = useState(null);
  const [lastAutomationResult, setLastAutomationResult] = useState(null);

  const [busyAction, setBusyAction] = useState("");
  const [booting, setBooting] = useState(true);
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const [newSessionTitle, setNewSessionTitle] = useState(buildDefaultSessionTitle());
  const [toast, setToast] = useState(null);
  const [isNavigating, startNavigation] = useTransition();

  const activeDetail = activeSessionId ? sessionDetails[activeSessionId] : null;

  useEffect(() => {
    let mounted = true;

    async function bootstrap() {
      setBooting(true);
      try {
        const [sessionsResult] = await Promise.allSettled([
          loadSessions(),
          loadRuntimeConfig(false),
          loadWorkers(),
        ]);
        if (!mounted) {
          return;
        }
        if (sessionsResult.status === "fulfilled" && sessionsResult.value.length > 0) {
          startNavigation(() => {
            setActiveSessionId((current) => current || getSessionId(sessionsResult.value[0]));
          });
        }
      } catch (error) {
        showToast(translateServerValue(error.message || String(error), readPreferredLocale()), "danger");
      } finally {
        if (mounted) {
          setBooting(false);
        }
      }
    }

    void bootstrap();

    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    if (!activeSessionId || workspaceMode !== "project") {
      return;
    }
    void refreshActiveSession({ includeViewData: true, silent: true });
  }, [activeSessionId]);

  useEffect(() => {
    if (workspaceMode === "ops") {
      void Promise.allSettled([loadRuntimeConfig(false), loadWorkers()]);
      return;
    }
    if (!activeSessionId) {
      return;
    }
    void loadCurrentViewData(activeSessionId, projectView, executionView);
  }, [workspaceMode, projectView, executionView]);

  useEffect(() => {
    if (!activeSessionId || workspaceMode !== "project" || projectView === "requirement") {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        void refreshActiveSession({ includeViewData: true, silent: true });
      }
    }, AUTO_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [activeSessionId, workspaceMode, projectView, executionView]);

  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => setToast(null), 2800);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    writeNavigationState({
      workspaceMode,
      projectView,
      opsView,
      executionView,
      activeSessionId,
    });
  }, [workspaceMode, projectView, opsView, executionView, activeSessionId]);

  function showToast(message, tone = "neutral") {
    setToast({ message, tone });
  }

  function tr(key, params) {
    return translate(readPreferredLocale(), key, params);
  }

  async function runBusy(actionName, work) {
    setBusyAction(actionName);
    try {
      await work();
    } catch (error) {
      showToast(translateServerValue(error.message || String(error), readPreferredLocale()), "danger");
      throw error;
    } finally {
      setBusyAction("");
    }
  }

  function getRequirementEditor(sessionId, detail = null) {
    const existing = requirementEditors[sessionId];
    if (existing) {
      return existing;
    }
    const doc = getRequirementDoc(detail);
    return {
      title: doc ? read(doc, "title") || getSessionTitle(detail) : getSessionTitle(detail),
      content: doc
        ? read(doc, "content") || buildRequirementTemplate(read(doc, "title") || getSessionTitle(detail), readPreferredLocale())
        : buildRequirementTemplate(getSessionTitle(detail), readPreferredLocale()),
      userInput: "",
      assistantMessage: "",
      missingInformation: [],
      readyToDraft: false,
    };
  }

  function seedRequirementEditor(sessionId, detail) {
    setRequirementEditors((current) => {
      if (current[sessionId]) {
        return current;
      }
      return {
        ...current,
        [sessionId]: getRequirementEditor(sessionId, detail),
      };
    });
  }

  async function loadSessions() {
    const data = await apiRequest("/api/v0/sessions");
    const list = Array.isArray(data) ? data : [];
    setSessions(list);
    return list;
  }

  async function loadSessionDetail(sessionId) {
    const detail = await apiRequest(`/api/v0/sessions/${sessionId}`);
    setSessionDetails((current) => ({ ...current, [sessionId]: detail }));
    seedRequirementEditor(sessionId, detail);
    return detail;
  }

  async function listTicketsRaw(sessionId) {
    const data = await apiRequest(`/api/v0/sessions/${sessionId}/tickets`);
    return Array.isArray(data) ? data : [];
  }

  async function loadProgress(sessionId) {
    const response = await apiRequest(`/api/v0/sessions/${sessionId}/progress`, { allow404: true });
    if (response && !response.__notFound) {
      setProgressBySession((current) => ({ ...current, [sessionId]: response }));
      return response;
    }

    const detail = sessionDetails[sessionId] || await loadSessionDetail(sessionId);
    const tickets = await listTicketsRaw(sessionId);
    const fallback = buildFallbackProgress(detail, tickets);
    setProgressBySession((current) => ({ ...current, [sessionId]: fallback }));
    return fallback;
  }

  async function loadTicketInbox(sessionId) {
    const response = await apiRequest(`/api/v0/sessions/${sessionId}/ticket-inbox`, { allow404: true });
    if (response && !response.__notFound) {
      setTicketInboxBySession((current) => ({ ...current, [sessionId]: response }));
      ensureSelectedTicket(sessionId, arrayOf(response, "tickets"));
      return response;
    }

    const tickets = await listTicketsRaw(sessionId);
    const fallback = buildTicketInboxFallback(sessionId, tickets);
    setTicketInboxBySession((current) => ({ ...current, [sessionId]: fallback }));
    ensureSelectedTicket(sessionId, fallback.tickets);
    return fallback;
  }

  async function loadTaskBoard(sessionId) {
    const response = await apiRequest(`/api/v0/sessions/${sessionId}/task-board`, { allow404: true });
    const board = response && !response.__notFound
      ? response
      : { sessionId, unavailableReason: tr("taskBoardReadModelUnavailable") };
    setTaskBoardBySession((current) => ({ ...current, [sessionId]: board }));
    ensureSelectedTask(sessionId, flattenTasks(board));
    return board;
  }

  async function loadRunTimeline(sessionId) {
    const response = await apiRequest(`/api/v0/sessions/${sessionId}/run-timeline?limit=40`, { allow404: true });
    const timeline = response && !response.__notFound
      ? response
      : { sessionId, unavailableReason: tr("runTimelineReadModelUnavailable") };
    setRunTimelineBySession((current) => ({ ...current, [sessionId]: timeline }));
    ensureSelectedRun(sessionId, arrayOf(timeline, "items"));
    return timeline;
  }

  async function loadCloneRepoPublication(sessionId) {
    const response = await apiRequest(`/api/v0/sessions/${sessionId}/delivery/clone-repo`, { allow404: true });
    setCloneRepoBySession((current) => {
      if (response && !response.__notFound) {
        return { ...current, [sessionId]: response };
      }
      const next = { ...current };
      delete next[sessionId];
      return next;
    });
    return response && !response.__notFound ? response : null;
  }

  async function loadTicketEvents(ticketId) {
    const events = await apiRequest(`/api/v0/tickets/${ticketId}/events`);
    setTicketEventsById((current) => ({ ...current, [ticketId]: Array.isArray(events) ? events : [] }));
    return events;
  }

  async function loadRuntimeConfig(resetEditor) {
    const config = await apiRequest("/api/v0/runtime/llm-config");
    setRuntimeConfig(config);
    setRuntimeEditor((current) => (resetEditor || !current ? buildRuntimeEditor(config) : current));
    return config;
  }

  async function loadWorkers() {
    const nextWorkers = await apiRequest("/api/v0/workforce/workers?limit=256");
    setWorkers(nextWorkers);
    return nextWorkers;
  }

  async function loadCurrentViewData(sessionId, nextProjectView = projectView, nextExecutionView = executionView) {
    if (!sessionId) {
      return;
    }
    switch (nextProjectView) {
      case "tickets":
        await loadTicketInbox(sessionId);
        break;
      case "execution":
        if (nextExecutionView === "runs") {
          await loadRunTimeline(sessionId);
        } else {
          await loadTaskBoard(sessionId);
        }
        break;
      case "delivery":
        await loadCloneRepoPublication(sessionId);
        break;
      default:
        break;
    }
  }

  async function refreshActiveSession({ includeViewData = false, silent = false } = {}) {
    if (!activeSessionId) {
      return;
    }
    try {
      await loadSessionDetail(activeSessionId);
      await loadProgress(activeSessionId);
      if (includeViewData) {
        await loadCurrentViewData(activeSessionId);
      }
      if (!silent) {
        showToast(tr("workspaceRefreshed"), "neutral");
      }
    } catch (error) {
      if (!silent) {
        showToast(translateServerValue(error.message || String(error), readPreferredLocale()), "danger");
      }
    }
  }

  function ensureSelectedTicket(sessionId, tickets) {
    setSelectedTicketBySession((current) => {
      const existing = current[sessionId];
      if (existing && tickets.some((ticket) => getTicketId(ticket) === existing)) {
        return current;
      }
      return tickets[0] ? { ...current, [sessionId]: getTicketId(tickets[0]) } : current;
    });
  }

  function ensureSelectedTask(sessionId, tasks) {
    setSelectedTaskBySession((current) => {
      const existing = current[sessionId];
      if (existing && tasks.some((task) => read(task, "taskId") === existing)) {
        return current;
      }
      return tasks[0] ? { ...current, [sessionId]: read(tasks[0], "taskId") } : current;
    });
  }

  function ensureSelectedRun(sessionId, items) {
    setSelectedRunBySession((current) => {
      const existing = current[sessionId];
      if (existing && items.some((item) => read(item, "runId") === existing)) {
        return current;
      }
      return items[0] ? { ...current, [sessionId]: read(items[0], "runId") } : current;
    });
  }

  function selectSession(sessionId) {
    startNavigation(() => {
      setWorkspaceMode("project");
      setActiveSessionId(sessionId);
    });
  }

  function switchWorkspace(mode) {
    startNavigation(() => setWorkspaceMode(mode));
  }

  function switchProjectView(nextView) {
    startNavigation(() => {
      setWorkspaceMode("project");
      setProjectView(nextView);
    });
  }

  function switchOpsView(nextView) {
    startNavigation(() => {
      setWorkspaceMode("ops");
      setOpsView(nextView);
    });
  }

  function switchExecutionView(nextView) {
    startNavigation(() => {
      setProjectView("execution");
      setExecutionView(nextView);
    });
  }

  function openCreateDialog() {
    setNewSessionTitle(buildDefaultSessionTitle());
    setCreateDialogOpen(true);
  }

  function closeCreateDialog() {
    if (busyAction !== "create-session") {
      setCreateDialogOpen(false);
    }
  }

  function updateRequirementField(field, value) {
    if (!activeSessionId) {
      return;
    }
    setRequirementEditors((current) => ({
      ...current,
      [activeSessionId]: {
        ...getRequirementEditor(activeSessionId, activeDetail),
        [field]: value,
      },
    }));
  }

  function updateRuntimeField(scope, field, value) {
    setRuntimeEditor((current) => {
      if (!current) {
        return current;
      }
      if (scope === "root") {
        return { ...current, [field]: value };
      }
      return {
        ...current,
        [scope]: {
          ...current[scope],
          [field]: value,
        },
      };
    });
  }

  async function createSession() {
    const title = newSessionTitle.trim();
    if (!title) {
      showToast(tr("sessionTitleRequired"), "danger");
      return;
    }

    await runBusy("create-session", async () => {
      const created = await apiRequest("/api/v0/sessions", {
        method: "POST",
        body: { title },
      });
      await loadSessions();
      setCreateDialogOpen(false);
      startNavigation(() => {
        setWorkspaceMode("project");
        setProjectView("requirement");
        setActiveSessionId(getSessionId(created));
      });
      showToast(tr("sessionCreated"), "active");
    });
  }

  async function runSessionCommand(command) {
    if (!activeSessionId) {
      return;
    }
    if (command === "complete" && !window.confirm(tr("confirmCompleteSession"))) {
      return;
    }
    await runBusy(`session-${command}`, async () => {
      await apiRequest(`/api/v0/sessions/${activeSessionId}/${command}`, { method: "POST" });
      await Promise.allSettled([loadSessions(), refreshActiveSession({ includeViewData: true, silent: true })]);
      const commandLabel = command === "complete" ? tr("completeSession") : tr(command);
      showToast(tr("sessionCommandDone", { command: commandLabel }), "active");
    });
  }

  async function ensureRequirementDoc(sessionId) {
    const detail = sessionDetails[sessionId] || await loadSessionDetail(sessionId);
    const currentDoc = getRequirementDoc(detail);
    if (currentDoc) {
      return getDocId(currentDoc);
    }
    const editor = getRequirementEditor(sessionId, detail);
    const created = await apiRequest(`/api/v0/sessions/${sessionId}/requirement-docs`, {
      method: "POST",
      body: { title: editor.title.trim() || getSessionTitle(detail) },
    });
    await loadSessionDetail(sessionId);
    return getDocId(created);
  }

  async function generateRequirementDraft(persist) {
    if (!activeSessionId) {
      return;
    }
    await runBusy(persist ? "draft-persist" : "draft-analyze", async () => {
      const editor = getRequirementEditor(activeSessionId, activeDetail);
      const doc = getRequirementDoc(activeDetail);
      const response = await apiRequest(`/api/v0/sessions/${activeSessionId}/requirement-agent/drafts`, {
        method: "POST",
        body: {
          title: editor.title.trim() || getSessionTitle(activeDetail),
          user_input: editor.userInput.trim(),
          doc_id: doc ? getDocId(doc) : null,
          persist,
        },
      });

      setRequirementEditors((current) => ({
        ...current,
        [activeSessionId]: {
          ...editor,
          content: read(response, "content") || editor.content,
          assistantMessage: read(response, "assistantMessage") || "",
          readyToDraft: Boolean(read(response, "readyToDraft")),
          missingInformation: arrayOf(response, "missingInformation"),
        },
      }));

      await Promise.allSettled([loadSessionDetail(activeSessionId), loadProgress(activeSessionId)]);
      showToast(persist ? tr("requirementDraftGenerated") : tr("requirementGapAnalyzed"), "active");
    });
  }

  async function saveRequirement() {
    if (!activeSessionId) {
      return;
    }
    await runBusy("save-requirement", async () => {
      const editor = getRequirementEditor(activeSessionId, activeDetail);
      const content = editor.content.trim();
      if (!content) {
        throw new Error(tr("requirementContentRequired"));
      }
      const docId = await ensureRequirementDoc(activeSessionId);
      await apiRequest(`/api/v0/requirement-docs/${docId}/content`, {
        method: "PUT",
        body: { content },
      });
      await Promise.allSettled([loadSessionDetail(activeSessionId), loadProgress(activeSessionId)]);
      showToast(tr("requirementSaved"), "active");
    });
  }

  async function confirmRequirement() {
    if (!activeSessionId) {
      return;
    }
    await runBusy("confirm-requirement", async () => {
      const detail = sessionDetails[activeSessionId] || await loadSessionDetail(activeSessionId);
      const doc = getRequirementDoc(detail);
      if (!doc) {
        throw new Error(tr("requirementDocMissing"));
      }
      await apiRequest(`/api/v0/requirement-docs/${getDocId(doc)}/confirm`, { method: "POST" });
      await Promise.allSettled([
        loadSessionDetail(activeSessionId),
        loadProgress(activeSessionId),
        loadTicketInbox(activeSessionId),
      ]);
      showToast(tr("requirementConfirmed"), "active");
    });
  }

  async function submitTicketResponse(ticketId) {
    const body = (ticketReplies[ticketId] || "").trim();
    if (!body) {
      showToast(tr("ticketReplyRequired"), "danger");
      return;
    }
    await runBusy("reply-ticket", async () => {
      const ticket = findTicketById(ticketInboxBySession[activeSessionId], ticketId);
      const dataJson = JSON.stringify({
        source: "mission_room",
        request_kind: inferRequestKind(ticket),
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
      setTicketReplies((current) => ({ ...current, [ticketId]: "" }));
      await Promise.allSettled([
        loadTicketInbox(activeSessionId),
        loadProgress(activeSessionId),
        loadTicketEvents(ticketId),
      ]);
      showToast(tr("ticketReplySubmitted"), "active");
    });
  }

  async function publishCloneRepo() {
    if (!activeSessionId) {
      return;
    }
    await runBusy("publish-clone", async () => {
      const response = await apiRequest(`/api/v0/sessions/${activeSessionId}/delivery/clone-repo`, { method: "POST" });
      setCloneRepoBySession((current) => ({ ...current, [activeSessionId]: response }));
      showToast(tr("clonePublished"), "active");
    });
  }

  async function copyCloneCommand(command) {
    try {
      await navigator.clipboard.writeText(command);
      showToast(tr("cloneCopied"), "active");
    } catch {
      showToast(tr("copyFailed"), "danger");
    }
  }

  async function testRuntimeConfig() {
    await runBusy("test-runtime", async () => {
      const probe = await apiRequest("/api/v0/runtime/llm-config:test", {
        method: "POST",
        body: buildRuntimeRequest(runtimeEditor),
      });
      setRuntimeProbe(probe);
      showToast(read(probe, "allOk") ? tr("runtimeProbePassed") : tr("runtimeProbeFailed"), read(probe, "allOk") ? "active" : "danger");
    });
  }

  async function applyRuntimeConfig() {
    await runBusy("apply-runtime", async () => {
      await apiRequest("/api/v0/runtime/llm-config:apply", {
        method: "POST",
        body: buildRuntimeRequest(runtimeEditor),
      });
      await loadRuntimeConfig(true);
      showToast(tr("runtimeApplied"), "active");
    });
  }

  async function runAutomation(path, successMessageKey) {
    await runBusy(`automation-${path}`, async () => {
      const payload = await apiRequest(path, { method: "POST", body: {} });
      setLastAutomationResult({
        action: path,
        executedAt: new Date().toISOString(),
        payload,
      });
      await Promise.allSettled([
        loadWorkers(),
        activeSessionId ? loadProgress(activeSessionId) : Promise.resolve(null),
        activeSessionId ? loadCurrentViewData(activeSessionId) : Promise.resolve(null),
      ]);
      showToast(tr(successMessageKey), "active");
    });
  }

  async function selectTicket(ticketId) {
    setSelectedTicketBySession((current) => ({ ...current, [activeSessionId]: ticketId }));
    await loadTicketEvents(ticketId);
  }

  function selectTask(taskId) {
    setSelectedTaskBySession((current) => ({ ...current, [activeSessionId]: taskId }));
  }

  function selectRun(runId) {
    setSelectedRunBySession((current) => ({ ...current, [activeSessionId]: runId }));
  }

  const requirementEditor = activeSessionId
    ? getRequirementEditor(activeSessionId, activeDetail)
    : { title: "", content: "", userInput: "", assistantMessage: "", missingInformation: [], readyToDraft: false };

  return {
    sessions,
    workspaceMode,
    projectView,
    opsView,
    executionView,
    activeSessionId,
    activeDetail,
    sessionDetails,
    progressBySession,
    ticketInboxBySession,
    taskBoardBySession,
    runTimelineBySession,
    cloneRepoBySession,
    ticketEventsById,
    runtimeConfig,
    runtimeEditor,
    runtimeProbe,
    workers,
    lastAutomationResult,
    requirementEditor,
    ticketReplies,
    selectedTicketId: activeSessionId ? selectedTicketBySession[activeSessionId] || null : null,
    selectedTaskId: activeSessionId ? selectedTaskBySession[activeSessionId] || null : null,
    selectedRunId: activeSessionId ? selectedRunBySession[activeSessionId] || null : null,
    busyAction,
    booting,
    createDialogOpen,
    newSessionTitle,
    toast,
    isNavigating,
    setNewSessionTitle,
    openCreateDialog,
    closeCreateDialog,
    createSession,
    selectSession,
    switchWorkspace,
    switchProjectView,
    switchOpsView,
    switchExecutionView,
    refreshActiveSession,
    runSessionCommand,
    generateRequirementDraft,
    saveRequirement,
    confirmRequirement,
    updateRequirementField,
    updateRuntimeField,
    selectTicket,
    selectTask,
    selectRun,
    setTicketReplies,
    submitTicketResponse,
    publishCloneRepo,
    copyCloneCommand,
    loadRuntimeConfig,
    loadWorkers,
    testRuntimeConfig,
    applyRuntimeConfig,
    runAutomation,
  };
}

function buildDefaultSessionTitle() {
  const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
  return `mission-${stamp}`;
}

function readNavigationState() {
  if (typeof window === "undefined") {
    return {
      workspaceMode: "project",
      projectView: "overview",
      opsView: "runtime",
      executionView: "tasks",
      activeSessionId: null,
    };
  }
  const params = new URLSearchParams(window.location.search);
  return {
    workspaceMode: params.get("workspace") === "ops" ? "ops" : "project",
    projectView: params.get("view") || "overview",
    opsView: params.get("ops") || "runtime",
    executionView: params.get("execution") || "tasks",
    activeSessionId: params.get("session"),
  };
}

function writeNavigationState(state) {
  if (typeof window === "undefined") {
    return;
  }
  const url = new URL(window.location.href);
  url.searchParams.set("workspace", state.workspaceMode);
  url.searchParams.set("view", state.projectView);
  url.searchParams.set("ops", state.opsView);
  url.searchParams.set("execution", state.executionView);
  if (state.activeSessionId) {
    url.searchParams.set("session", state.activeSessionId);
  } else {
    url.searchParams.delete("session");
  }
  window.history.replaceState({}, "", `${url.pathname}?${url.searchParams.toString()}`);
}

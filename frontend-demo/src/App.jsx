import React from "react";
import { OPS_VIEWS, PROJECT_VIEWS, fallbackSessionSummary, formatDateTime, getRequirementDoc, getSessionId, getSessionTitle, read } from "./controlPlane";
import { ActionButton, CreateSessionDialog, EmptyCard, Metric, Panel, PhaseRibbon, StatusBadge, Toast } from "./components";
import { I18nProvider, readPreferredLocale, useI18n } from "./i18n";
import { DeliveryPage, ExecutionPage, OverviewPage, RequirementPage, RuntimePage, TicketsPage, WorkersPage } from "./pages";
import { useMissionRoom } from "./useMissionRoom";

export function App() {
  const room = useMissionRoom();
  const [locale, setLocale] = React.useState(readLocale);
  React.useEffect(() => writeLocale(locale), [locale]);
  const activeDetail = room.activeSessionId ? room.sessionDetails[room.activeSessionId] : null;
  const activeProgress = room.activeSessionId ? room.progressBySession[room.activeSessionId] : null;

  return (
    <I18nProvider locale={locale}>
      <div className="app-shell">
        <Sidebar room={room} activeProgress={activeProgress} />
        <main className="workspace-shell">
          <Header room={room} activeDetail={activeDetail} activeProgress={activeProgress} locale={locale} setLocale={setLocale} />
          <WorkspaceBody room={room} activeDetail={activeDetail} activeProgress={activeProgress} />
        </main>
      </div>
      {room.createDialogOpen ? (
        <CreateSessionDialog
          pending={room.busyAction === "create-session"}
          title={room.newSessionTitle}
          onTitleChange={room.setNewSessionTitle}
          onClose={room.closeCreateDialog}
          onCreate={room.createSession}
        />
      ) : null}
      <Toast toast={room.toast} />
    </I18nProvider>
  );
}

function Sidebar({ room, activeProgress }) {
  const { t, serverText, label } = useI18n();
  const activeCount = room.sessions.filter((session) => read(session, "status") === "ACTIVE").length;
  const pausedCount = room.sessions.filter((session) => read(session, "status") === "PAUSED").length;
  const readyWorkers = room.workers ? read(room.workers, "readyWorkers") || 0 : 0;

  return (
    <aside className="sidebar-shell">
      <Panel title={t("missionRoom")} kicker={t("agentxControlPlane")} dark>
        <p className="lead">{t("missionLead")}</p>
      </Panel>
      <Panel title={t("systemFocus")} kicker={t("summary")} dark>
        <p className="focus-copy">{activeProgress ? `${label(read(activeProgress, "phase") || "DRAFTING")} · ${serverText(read(activeProgress, "blockerSummary")) || "-"}` : t("selectExistingSession")}</p>
        <div className="mini-metrics">
          <Metric label={t("sessions")} value={room.sessions.length} inverse />
          <Metric label={t("active")} value={activeCount} inverse />
          <Metric label={t("paused")} value={pausedCount} inverse />
          <Metric label={t("readyWorkers")} value={readyWorkers} inverse />
        </div>
      </Panel>

      <div className="sidebar-section">
        <div className="section-head">
          <div>
            <p className="eyebrow">{t("sessions")}</p>
            <h2>{t("sessionList")}</h2>
          </div>
          <ActionButton tone="ghost" small onClick={room.openCreateDialog}>{t("create")}</ActionButton>
        </div>
        <div className="session-list">
          {room.sessions.length === 0 ? <EmptyCard title={t("selectSession")} body={t("noSessionYet")} /> : room.sessions.map((session) => {
            const sessionId = getSessionId(session);
            const detail = room.sessionDetails[sessionId] || session;
            const progress = room.progressBySession[sessionId];
            return (
              <button key={sessionId} type="button" className={`session-card ${room.activeSessionId === sessionId ? "is-active" : ""}`} onClick={() => room.selectSession(sessionId)}>
                <div className="session-card__top">
                  <strong>{getSessionTitle(session)}</strong>
                  <StatusBadge value={read(session, "status") || "UNKNOWN"} />
                </div>
                <p className="session-card__body">{serverText(read(progress, "blockerSummary")) || fallbackSessionSummary(getRequirementDoc(detail))}</p>
                <div className="session-card__foot">
                  <span>{label(read(progress, "phase") || read(session, "status") || "ACTIVE")}</span>
                  <span>{`${read(read(progress, "ticketCounts"), "waitingUser") || 0} ${t("waitingUser")}`}</span>
                  <span>{`${read(read(progress, "runCounts"), "running") || 0} ${t("runs")}`}</span>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </aside>
  );
}

function Header({ room, activeDetail, activeProgress, locale, setLocale }) {
  const { t, label } = useI18n();
  const isProject = room.workspaceMode === "project";
  const title = isProject ? (activeDetail ? getSessionTitle(activeDetail) : t("selectSession")) : t("ops");
  const meta = isProject ? (activeDetail ? `${getSessionId(activeDetail)} · ${label(read(activeDetail, "status") || "ACTIVE")} · ${formatDateTime(read(activeDetail, "updatedAt"))}` : t("noActiveSession")) : t("operationsLead");

  return (
    <header className="workspace-header panel">
      <div className="workspace-header__top">
        <div className="workspace-heading">
          <div className="segmented">
            <button type="button" className={`segmented__button ${room.workspaceMode === "project" ? "is-active" : ""}`} onClick={() => room.switchWorkspace("project")}>{t("project")}</button>
            <button type="button" className={`segmented__button ${room.workspaceMode === "ops" ? "is-active" : ""}`} onClick={() => room.switchWorkspace("ops")}>{t("ops")}</button>
            <button type="button" className={`segmented__button ${locale === "zh" ? "is-active" : ""}`} onClick={() => setLocale("zh")}>ZH</button>
            <button type="button" className={`segmented__button ${locale === "en" ? "is-active" : ""}`} onClick={() => setLocale("en")}>EN</button>
          </div>
          <p className="eyebrow">{isProject ? t("projectWorkspace") : t("operations")}</p>
          <h2>{title}</h2>
          <p className="meta-line">{meta}</p>
        </div>
        <div className="header-actions">
          <ActionButton tone="ghost" onClick={() => room.refreshActiveSession({ includeViewData: true })}>{t("refresh")}</ActionButton>
          {isProject && activeDetail ? (
            <>
              {read(activeDetail, "status") === "ACTIVE" ? <ActionButton tone="ghost" onClick={() => room.runSessionCommand("pause")}>{t("pause")}</ActionButton> : null}
              {read(activeDetail, "status") === "PAUSED" ? <ActionButton tone="primary" onClick={() => room.runSessionCommand("resume")}>{t("resume")}</ActionButton> : null}
              <ActionButton tone="primary" disabled={!read(activeProgress, "canCompleteSession")} onClick={() => room.runSessionCommand("complete")}>{t("completeSession")}</ActionButton>
            </>
          ) : null}
        </div>
      </div>
      {isProject && activeDetail ? <PhaseRibbon current={read(activeProgress, "phase") || "DRAFTING"} /> : null}
      <nav className="view-tabs">
        {(isProject ? PROJECT_VIEWS : OPS_VIEWS).map((view) => (
          <button key={view.id} type="button" className={`view-tabs__button ${(isProject ? room.projectView : room.opsView) === view.id ? "is-active" : ""}`} onClick={() => (isProject ? room.switchProjectView(view.id) : room.switchOpsView(view.id))}>{translateViewLabel(view.id, t)}</button>
        ))}
      </nav>
      {room.booting || room.isNavigating ? <div className="loading-line">{t("syncing")}</div> : null}
    </header>
  );
}

function WorkspaceBody({ room, activeDetail, activeProgress }) {
  const { t } = useI18n();
  if (room.workspaceMode === "ops") {
    return room.opsView === "workers" ? <WorkersPage room={room} /> : <RuntimePage room={room} />;
  }
  if (!room.activeSessionId || !activeDetail) {
    return <section className="body-shell"><EmptyCard title={t("selectSession")} body={t("selectExistingSession")} large><ActionButton tone="primary" onClick={room.openCreateDialog}>{t("createSession")}</ActionButton></EmptyCard></section>;
  }
  switch (room.projectView) {
    case "requirement": return <RequirementPage room={room} detail={activeDetail} progress={activeProgress} />;
    case "tickets": return <TicketsPage room={room} detail={activeDetail} />;
    case "execution": return <ExecutionPage room={room} />;
    case "delivery": return <DeliveryPage room={room} progress={activeProgress} />;
    default: return <OverviewPage room={room} detail={activeDetail} progress={activeProgress} onNavigate={room.switchProjectView} />;
  }
}

function translateViewLabel(id, t) {
  const map = {
    overview: t("summary"),
    requirement: t("requirement"),
    tickets: t("inbox"),
    execution: t("execution"),
    delivery: t("delivery"),
    runtime: t("runtime"),
    workers: t("workers"),
  };
  return map[id] || id;
}

function readLocale() {
  return readPreferredLocale();
}

function writeLocale(locale) {
  if (typeof window === "undefined") {
    return;
  }
  window.localStorage.setItem("agentx-ui-locale", locale);
  const url = new URL(window.location.href);
  url.searchParams.set("lang", locale);
  window.history.replaceState({}, "", `${url.pathname}?${url.searchParams.toString()}`);
}

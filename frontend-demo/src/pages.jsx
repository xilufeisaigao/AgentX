import React from "react";
import {
  arrayOf,
  findRunById,
  findTaskById,
  findTicketById,
  formatDateTime,
  getRequirementDoc,
  getSessionTitle,
  getTicketId,
  inferRequestKind,
  inferTicketQuestion,
  prettyJson,
  read,
} from "./controlPlane";
import { ActionButton, EmptyCard, Field, Metric, Panel, PropertyRow, StatusBadge } from "./components";
import { useI18n } from "./i18n";

export function OverviewPage({ room, detail, progress, onNavigate }) {
  const { t, serverText } = useI18n();
  const requirement = read(progress, "requirement") || getRequirementDoc(detail);
  const taskCounts = read(progress, "taskCounts") || {};
  const ticketCounts = read(progress, "ticketCounts") || {};
  const runCounts = read(progress, "runCounts") || {};
  const latestRun = read(progress, "latestRun");
  const action = buildPrimaryAction(progress, requirement, onNavigate, t);

  return (
    <section className="body-shell">
      <div className="hero-card panel">
        <div>
          <p className="eyebrow">{t("currentBlocker")}</p>
          <h3>{serverText(read(progress, "blockerSummary")) || "-"}</h3>
          <p className="hero-copy">{serverText(read(progress, "primaryAction")) || "-"}</p>
        </div>
        <div className="hero-side">
          <div className="hero-side__action">
            <p className="eyebrow">{t("primaryAction")}</p>
            <strong>{action.label}</strong>
            <p>{action.description}</p>
            <ActionButton tone="primary" onClick={action.onClick}>{action.button}</ActionButton>
          </div>
          <div className="hero-side__metrics">
            <Metric label={t("waitingUser")} value={read(ticketCounts, "waitingUser") || 0} />
            <Metric label={t("runningRuns")} value={read(runCounts, "running") || 0} />
            <Metric label={t("canComplete")} value={read(progress, "canCompleteSession") ? t("yes") : t("no")} />
          </div>
        </div>
      </div>

      <div className="panel-grid panel-grid--overview">
        <Panel title={t("requirement")} kicker={t("requirementLedger")}>
          <div className="property-list">
            <PropertyRow label={t("titleLabel")} value={requirement ? read(requirement, "title") : t("notCreated")} />
            <PropertyRow label={t("statusLabel")} value={<StatusBadge value={requirement ? read(requirement, "status") : "MISSING"} />} />
            <PropertyRow label={t("currentVersionLabel")} value={requirement ? read(requirement, "currentVersion") || 0 : 0} />
            <PropertyRow label={t("confirmedVersionLabel")} value={requirement ? read(requirement, "confirmedVersion") || "-" : "-"} />
          </div>
        </Panel>

        <Panel title={t("flowSignal")} kicker={t("summary")}>
          <div className="stat-stack">
            <Metric label={t("tasks")} value={read(taskCounts, "total") || 0} />
            <Metric label={t("openTicketsLabel")} value={read(ticketCounts, "open") || 0} />
            <Metric label={t("succeededRunsLabel")} value={read(runCounts, "succeeded") || 0} />
            <Metric label={t("failedRunsLabel")} value={read(runCounts, "failed") || 0} />
          </div>
        </Panel>

        <Panel title={t("completionGate")} kicker={t("delivery")}>
          <ul className="compact-list">
            {arrayOf(progress, "completionBlockers").length > 0
              ? arrayOf(progress, "completionBlockers").map((item) => <li key={item}>{serverText(item)}</li>)
              : <li>{t("noCompletionBlocker")}</li>}
          </ul>
        </Panel>

        <Panel title={latestRun ? read(latestRun, "taskTitle") || read(latestRun, "runId") : t("latestRun")} kicker={t("latestRun")}>
          {latestRun ? (
            <>
              <p className="panel-copy">{serverText(read(latestRun, "eventBody")) || read(latestRun, "status") || "-"}</p>
              <div className="pill-row">
                <StatusBadge value={read(latestRun, "status") || "UNKNOWN"} />
                <StatusBadge value={read(latestRun, "runKind") || "IMPL"} soft />
                <StatusBadge value={read(latestRun, "workerId") || "no-worker"} soft />
              </div>
            </>
          ) : (
            <p className="panel-copy">{t("noRecentRun")}</p>
          )}
        </Panel>
      </div>
    </section>
  );
}

export function RequirementPage({ room, detail, progress }) {
  const { t, label, serverText } = useI18n();
  const requirement = getRequirementDoc(detail);
  const editor = room.requirementEditor;

  return (
    <section className="body-shell">
      <div className="panel-grid panel-grid--requirement">
        <Panel title={requirement ? read(requirement, "title") || t("requirement") : t("requirement")} kicker={t("requirementLedger")}>
          <div className="property-list">
            <PropertyRow label={t("statusLabel")} value={<StatusBadge value={requirement ? read(requirement, "status") : "DRAFTING"} />} />
            <PropertyRow label={t("currentVersionLabel")} value={requirement ? read(requirement, "currentVersion") || 0 : 0} />
            <PropertyRow label={t("confirmedVersionLabel")} value={requirement ? read(requirement, "confirmedVersion") || "-" : "-"} />
            <PropertyRow label={t("updatedAtLabel")} value={formatDateTime(requirement ? read(requirement, "updatedAt") : null)} />
          </div>
          <p className="support-copy">{t("requirementRevisionHint")}</p>
        </Panel>

        <Panel title={t("workingBrief")} kicker={t("draftAssistant")}>
          <Field label={t("requirementTitle")}>
            <input value={editor.title} onChange={(event) => room.updateRequirementField("title", event.target.value)} />
          </Field>
          <Field label={t("requirementSupplement")}>
            <textarea
              value={editor.userInput}
              onChange={(event) => room.updateRequirementField("userInput", event.target.value)}
              placeholder={t("requirementSupplementPlaceholder")}
            />
          </Field>
          <div className="button-row">
            <ActionButton tone="ghost" onClick={() => room.generateRequirementDraft(false)}>{t("analyzeGap")}</ActionButton>
            <ActionButton tone="primary" onClick={() => room.generateRequirementDraft(true)}>{t("generateDraft")}</ActionButton>
          </div>
          {editor.assistantMessage ? <p className="support-block">{serverText(editor.assistantMessage)}</p> : null}
          {editor.missingInformation?.length ? (
            <ul className="compact-list compact-list--chips">
              {editor.missingInformation.map((item) => <li key={item}>{serverText(item)}</li>)}
            </ul>
          ) : null}
        </Panel>

        <Panel title={t("documentEditor")} kicker={t("requirementStudio")} wide>
          <Field label={t("markdownContent")}>
            <textarea
              className="editor-textarea"
              value={editor.content}
              onChange={(event) => room.updateRequirementField("content", event.target.value)}
              placeholder={t("requirementContentPlaceholder")}
            />
          </Field>
          <div className="button-row">
            <ActionButton tone="primary" onClick={room.saveRequirement}>{t("saveVersion")}</ActionButton>
            <ActionButton tone="ghost" disabled={!requirement} onClick={room.confirmRequirement}>{t("confirmRequirement")}</ActionButton>
          </div>
          <p className="support-copy">{t("requirementTemplateHint", { phase: label(read(progress, "phase") || "DRAFTING") })}</p>
        </Panel>
      </div>
    </section>
  );
}

export function TicketsPage({ room, detail }) {
  const { t, serverText, label } = useI18n();
  const inbox = room.ticketInboxBySession[room.activeSessionId];
  const tickets = arrayOf(inbox, "tickets");
  const selected = room.selectedTicketId ? findTicketById(inbox, room.selectedTicketId) : tickets[0] || null;
  const ticketId = selected ? getTicketId(selected) : null;
  const events = ticketId ? room.ticketEventsById[ticketId] || [] : [];

  return (
    <section className="body-shell body-shell--split">
      <div className="main-column">
        <Panel title={t("decisionInbox")} kicker={t("waitingUser")}>
          {tickets.length === 0 ? (
            <EmptyCard title={t("ticketNone")} body={t("noTicketWaiting")} />
          ) : (
            <div className="list-stack">
              {tickets.map((ticket) => (
                <button
                  key={getTicketId(ticket)}
                  type="button"
                  className={`list-card ${room.selectedTicketId === getTicketId(ticket) ? "is-active" : ""}`}
                  onClick={() => room.selectTicket(getTicketId(ticket))}
                >
                  <div className="list-card__head">
                    <div>
                      <p className="eyebrow">{label(inferRequestKind(ticket) || read(ticket, "type") || "UNKNOWN")}</p>
                      <strong>{read(ticket, "title") || getTicketId(ticket)}</strong>
                    </div>
                    <StatusBadge value={read(ticket, "status") || "UNKNOWN"} />
                  </div>
                  <p className="panel-copy">{serverText(inferTicketQuestion(ticket))}</p>
                  <div className="list-card__foot">
                    <span>{read(ticket, "sourceRunId") || "no-run"}</span>
                    <span>{formatDateTime(read(ticket, "updatedAt") || read(ticket, "latestEventAt"))}</span>
                  </div>
                </button>
              ))}
            </div>
          )}
        </Panel>
      </div>

      <div className="inspector-column">
        <Panel title={selected ? read(selected, "title") || ticketId : t("inspector")} kicker={t("inspector")}>
          {selected ? (
            <>
              <p className="panel-copy">{serverText(inferTicketQuestion(selected))}</p>
              <div className="pill-row">
                <StatusBadge value={read(selected, "type") || "UNKNOWN"} soft />
                <StatusBadge value={read(selected, "status") || "UNKNOWN"} />
                <StatusBadge value={read(selected, "sourceRunId") || "no-run"} soft />
              </div>
              <Field label={t("replyContent")}>
                <textarea
                  value={room.ticketReplies[ticketId] || ""}
                  onChange={(event) => room.setTicketReplies((current) => ({ ...current, [ticketId]: event.target.value }))}
                  placeholder={t("ticketReplyPlaceholder")}
                />
              </Field>
              <ActionButton tone="primary" disabled={!read(selected, "needsUserAction")} onClick={() => room.submitTicketResponse(ticketId)}>
                {t("submitResponse")}
              </ActionButton>
            </>
          ) : (
            <p className="panel-copy">{t("noTicketSelected")}</p>
          )}
        </Panel>

        <Panel title={t("context")} kicker={t("trace")}>
          {selected ? (
            <>
              <PropertyRow label={t("sessionLabel")} value={getSessionTitle(detail)} />
              <PropertyRow label={t("requestKindLabel")} value={label(inferRequestKind(selected) || "-")} />
              <PropertyRow label={t("sourceTaskLabel")} value={read(selected, "sourceTaskId") || "-"} />
              <PropertyRow label={t("sourceRunLabel")} value={read(selected, "sourceRunId") || "-"} />
              <details className="trace-block">
                <summary>{t("rawPayload")}</summary>
                <pre>{prettyJson(read(selected, "payloadJson") || "{}")}</pre>
              </details>
              {events.length > 0 ? (
                <details className="trace-block" open>
                  <summary>{t("eventTrace")}</summary>
                  <div className="trace-list">
                    {events.map((item, index) => (
                      <div key={`${read(item, "eventType")}-${index}`} className="trace-list__item">
                        <strong>{read(item, "eventType")}</strong>
                        <span>{formatDateTime(read(item, "createdAt"))}</span>
                        <p>{serverText(read(item, "body")) || "-"}</p>
                      </div>
                    ))}
                  </div>
                </details>
              ) : null}
            </>
          ) : (
            <p className="panel-copy">{t("noTicketContext")}</p>
          )}
        </Panel>
      </div>
    </section>
  );
}

export function ExecutionPage({ room }) {
  const { t, serverText } = useI18n();
  const board = room.taskBoardBySession[room.activeSessionId];
  const timeline = room.runTimelineBySession[room.activeSessionId];
  const selectedTask = room.selectedTaskId ? findTaskById(board, room.selectedTaskId) : null;
  const selectedRun = room.selectedRunId ? findRunById(timeline, room.selectedRunId) : null;

  return (
    <section className="body-shell body-shell--split">
      <div className="main-column">
        <div className="subnav">
          <button type="button" className={`subnav__button ${room.executionView === "tasks" ? "is-active" : ""}`} onClick={() => room.switchExecutionView("tasks")}>{t("tasks")}</button>
          <button type="button" className={`subnav__button ${room.executionView === "runs" ? "is-active" : ""}`} onClick={() => room.switchExecutionView("runs")}>{t("runs")}</button>
        </div>

        {room.executionView === "tasks" ? (
          <Panel title={t("taskBoard")} kicker={t("execution")}>
            {board?.unavailableReason ? (
              <EmptyCard title={t("taskBoardUnavailable")} body={serverText(board.unavailableReason)} />
            ) : (
              <div className="module-stack">
                {arrayOf(board, "modules").map((module) => (
                  <section key={read(module, "moduleId") || read(module, "moduleName")} className="module-group">
                    <div className="module-group__head">
                      <div>
                        <p className="eyebrow">{read(module, "moduleId") || "module"}</p>
                        <h4>{read(module, "moduleName") || "Unnamed Module"}</h4>
                      </div>
                      <span>{arrayOf(module, "tasks").length} tasks</span>
                    </div>
                    <div className="list-stack">
                      {arrayOf(module, "tasks").map((task) => (
                        <button
                          key={read(task, "taskId")}
                          type="button"
                          className={`list-card ${room.selectedTaskId === read(task, "taskId") ? "is-active" : ""}`}
                          onClick={() => room.selectTask(read(task, "taskId"))}
                        >
                          <div className="list-card__head">
                            <div>
                              <p className="eyebrow">{read(task, "taskTemplateId") || "task"}</p>
                              <strong>{read(task, "title") || read(task, "taskId")}</strong>
                            </div>
                            <StatusBadge value={read(task, "status") || "UNKNOWN"} />
                          </div>
                          <div className="pill-row">
                            <StatusBadge value={read(task, "lastRunStatus") || "NO_RUN"} soft />
                            <StatusBadge value={read(task, "latestContextStatus") || "NO_CONTEXT"} soft />
                          </div>
                          <div className="list-card__foot">
                            <span>{read(task, "activeRunId") || read(task, "lastRunId") || "no-run"}</span>
                            <span>{formatDateTime(read(task, "lastRunUpdatedAt") || read(task, "latestContextCompiledAt"))}</span>
                          </div>
                        </button>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            )}
          </Panel>
        ) : (
          <Panel title={t("runTimeline")} kicker={t("execution")}>
            {timeline?.unavailableReason ? (
              <EmptyCard title={t("runTimelineUnavailable")} body={serverText(timeline.unavailableReason)} />
            ) : (
              <div className="list-stack">
                {arrayOf(timeline, "items").map((item) => (
                  <button
                    key={read(item, "runId")}
                    type="button"
                    className={`list-card ${room.selectedRunId === read(item, "runId") ? "is-active" : ""}`}
                    onClick={() => room.selectRun(read(item, "runId"))}
                  >
                    <div className="list-card__head">
                      <div>
                        <p className="eyebrow">{read(item, "eventType") || "RUN_EVENT"}</p>
                        <strong>{read(item, "taskTitle") || read(item, "runId")}</strong>
                      </div>
                      <StatusBadge value={read(item, "runStatus") || "UNKNOWN"} />
                    </div>
                    <p className="panel-copy">{serverText(read(item, "eventBody")) || "-"}</p>
                    <div className="list-card__foot">
                      <span>{read(item, "runKind") || "IMPL"}</span>
                      <span>{formatDateTime(read(item, "eventCreatedAt") || read(item, "startedAt"))}</span>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </Panel>
        )}
      </div>

      <div className="inspector-column">
        {room.executionView === "tasks" ? <TaskInspector task={selectedTask} board={board} /> : <RunInspector run={selectedRun} />}
      </div>
    </section>
  );
}

function TaskInspector({ task, board }) {
  const { t } = useI18n();
  return (
    <Panel title={task ? read(task, "title") || read(task, "taskId") : t("inspector")} kicker={t("inspector")}>
      {task ? (
        <>
          <div className="pill-row">
            <StatusBadge value={read(task, "status") || "UNKNOWN"} />
            <StatusBadge value={read(task, "lastRunStatus") || "NO_RUN"} soft />
            <StatusBadge value={read(task, "latestVerifyStatus") || "NO_VERIFY"} soft />
          </div>
          <div className="property-list">
            <PropertyRow label={t("templateLabel")} value={read(task, "taskTemplateId") || "-"} />
            <PropertyRow label={t("activeRunLabel")} value={read(task, "activeRunId") || read(task, "lastRunId") || "-"} />
            <PropertyRow label={t("contextSnapshotLabel")} value={read(task, "latestContextSnapshotId") || "-"} />
            <PropertyRow label={t("contextStatusLabel")} value={read(task, "latestContextStatus") || "-"} />
            <PropertyRow label={t("deliveryCommitLabel")} value={read(task, "latestDeliveryCommit") || "-"} />
            <PropertyRow label={t("verifyRunLabel")} value={read(task, "latestVerifyRunId") || "-"} />
          </div>
          <details className="trace-block">
            <summary>{t("dependenciesLabel")}</summary>
            <ul className="compact-list">
              {arrayOf(task, "dependencyTaskIds").length > 0
                ? arrayOf(task, "dependencyTaskIds").map((item) => <li key={item}>{item}</li>)
                : <li>{t("noDependencies")}</li>}
            </ul>
          </details>
          <details className="trace-block">
            <summary>{t("boardSnapshotLabel")}</summary>
            <pre>{prettyJson(board)}</pre>
          </details>
        </>
      ) : (
        <p className="panel-copy">{t("noTaskSelected")}</p>
      )}
    </Panel>
  );
}

function RunInspector({ run }) {
  const { t, serverText } = useI18n();
  return (
    <Panel title={run ? read(run, "runId") || "Run" : t("inspector")} kicker={t("inspector")}>
      {run ? (
        <>
          <div className="pill-row">
            <StatusBadge value={read(run, "runStatus") || "UNKNOWN"} />
            <StatusBadge value={read(run, "runKind") || "IMPL"} soft />
            <StatusBadge value={read(run, "workerId") || "no-worker"} soft />
          </div>
          <p className="panel-copy">{serverText(read(run, "eventBody")) || "-"}</p>
          <div className="property-list">
            <PropertyRow label={t("taskLabel")} value={read(run, "taskTitle") || read(run, "taskId") || "-"} />
            <PropertyRow label={t("branchLabel")} value={read(run, "branchName") || "-"} />
            <PropertyRow label={t("startedLabel")} value={formatDateTime(read(run, "startedAt"))} />
            <PropertyRow label={t("finishedLabel")} value={formatDateTime(read(run, "finishedAt"))} />
          </div>
          <details className="trace-block" open>
            <summary>{t("rawEventPayloadLabel")}</summary>
            <pre>{prettyJson(read(run, "eventDataJson") || "{}")}</pre>
          </details>
        </>
      ) : (
        <p className="panel-copy">{t("noRunSelected")}</p>
      )}
    </Panel>
  );
}

export function DeliveryPage({ room, progress }) {
  const { t, serverText } = useI18n();
  const delivery = read(progress, "delivery") || {};
  const clone = room.cloneRepoBySession[room.activeSessionId];

  return (
    <section className="body-shell">
      <div className="panel-grid panel-grid--delivery">
        <Panel title={read(progress, "canCompleteSession") ? t("completeSession") : t("completionGate")} kicker={t("completionGate")} wide>
          <p className="panel-copy">{serverText(read(progress, "blockerSummary")) || "-"}</p>
          <div className="stat-stack stat-stack--three">
            <Metric label={t("deliveryTagLabel")} value={read(delivery, "deliveryTagPresent") ? t("yes") : t("no")} />
            <Metric label={t("deliveredTasksLabel")} value={read(delivery, "deliveredTaskCount") || 0} />
            <Metric label={t("doneTasksLabel")} value={read(delivery, "doneTaskCount") || 0} />
          </div>
          <ul className="compact-list">
            {arrayOf(progress, "completionBlockers").length > 0
              ? arrayOf(progress, "completionBlockers").map((item) => <li key={item}>{serverText(item)}</li>)
              : <li>{t("noCompletionBlocker")}</li>}
          </ul>
        </Panel>

        <Panel title={clone ? read(clone, "repositoryName") || t("cloneRepo") : t("cloneRepo")} kicker={t("delivery")}>
          {clone ? (
            <>
              <p className="panel-copy">{read(clone, "cloneUrl") || ""}</p>
              <div className="code-snippet">{read(clone, "cloneCommand") || ""}</div>
              <div className="button-row">
                <ActionButton tone="ghost" onClick={() => room.copyCloneCommand(read(clone, "cloneCommand") || "")}>{t("copyCommand")}</ActionButton>
                <ActionButton tone="primary" onClick={room.publishCloneRepo}>{t("republish")}</ActionButton>
              </div>
            </>
          ) : (
            <>
              <p className="panel-copy">{t("noCloneAddress")}</p>
              <ActionButton tone="primary" onClick={room.publishCloneRepo}>{t("publishClone")}</ActionButton>
            </>
          )}
        </Panel>

        <Panel title={t("latestDeliverySignal")} kicker={t("trace")}>
          <div className="property-list">
            <PropertyRow label={t("latestCommitLabel")} value={read(delivery, "latestDeliveryCommit") || "-"} />
            <PropertyRow label={t("latestVerifyRunLabel")} value={read(delivery, "latestVerifyRunId") || "-"} />
            <PropertyRow label={t("verifyStatusLabel")} value={read(delivery, "latestVerifyStatus") || "-"} />
            <PropertyRow label={t("latestDeliveryTaskLabel")} value={read(delivery, "latestDeliveryTaskId") || "-"} />
          </div>
        </Panel>
      </div>
    </section>
  );
}

export function RuntimePage({ room }) {
  const { t } = useI18n();
  return (
    <section className="body-shell">
      <div className="panel-grid panel-grid--ops">
        <Panel title={t("runtimeEditor")} kicker={t("runtimeEditorOps")} wide>
          {room.runtimeEditor ? (
            <>
              <Field label={t("outputLanguage")}>
                <input value={room.runtimeEditor.outputLanguage} onChange={(event) => room.updateRuntimeField("root", "outputLanguage", event.target.value)} />
              </Field>
              <RuntimeProfileEditor title={t("requirementLlmTitle")} profile={room.runtimeEditor.requirementLlm} onChange={(field, value) => room.updateRuntimeField("requirementLlm", field, value)} />
              <RuntimeProfileEditor title={t("workerRuntimeLlmTitle")} profile={room.runtimeEditor.workerRuntimeLlm} onChange={(field, value) => room.updateRuntimeField("workerRuntimeLlm", field, value)} />
              <div className="button-row">
                <ActionButton tone="ghost" onClick={() => room.loadRuntimeConfig(true)}>{t("readConfig")}</ActionButton>
                <ActionButton tone="ghost" onClick={room.testRuntimeConfig}>{t("testConnectivity")}</ActionButton>
                <ActionButton tone="primary" onClick={room.applyRuntimeConfig}>{t("applyConfig")}</ActionButton>
              </div>
            </>
          ) : <p className="panel-copy">{t("loadingRuntime")}</p>}
        </Panel>

        <Panel title={`${t("versionLabel")} ${read(room.runtimeConfig, "version") || "-"}`} kicker={t("currentRuntime")}>
          {room.runtimeConfig ? (
            <>
              <RuntimeProfileSummary title={t("requirementProfileTitle")} profile={read(room.runtimeConfig, "requirementLlm")} />
              <RuntimeProfileSummary title={t("workerProfileTitle")} profile={read(room.runtimeConfig, "workerRuntimeLlm")} />
              {room.runtimeProbe ? <details className="trace-block" open><summary>{t("probeResultLabel")}</summary><pre>{prettyJson(room.runtimeProbe)}</pre></details> : null}
            </>
          ) : <p className="panel-copy">{t("noRuntimeSummary")}</p>}
        </Panel>
      </div>
    </section>
  );
}

export function WorkersPage({ room }) {
  const { t } = useI18n();
  const workers = room.workers;
  return (
    <section className="body-shell">
      <div className="panel-grid panel-grid--ops">
        <Panel title={t("poolSummary")} kicker={t("workers")}>
          <div className="stat-stack">
            <Metric label={t("totalLabel")} value={read(workers, "totalWorkers") || 0} />
            <Metric label={t("readyLabel")} value={read(workers, "readyWorkers") || 0} />
            <Metric label={t("disabledLabel")} value={read(workers, "disabledWorkers") || 0} />
          </div>
        </Panel>

        <Panel title={t("automations")} kicker={t("runtimeEditorOps")} wide>
          <div className="button-row">
            <ActionButton tone="ghost" onClick={() => room.loadWorkers()}>{t("refreshWorkers")}</ActionButton>
            <ActionButton tone="ghost" onClick={() => room.runAutomation("/api/v0/workforce/auto-provision", "autoProvisionDone")}>{t("autoProvision")}</ActionButton>
            <ActionButton tone="primary" onClick={() => room.runAutomation("/api/v0/workforce/runtime/auto-run", "autoRunDone")}>{t("autoRun")}</ActionButton>
            <ActionButton tone="ghost" onClick={() => room.runAutomation("/api/v0/execution/lease-recovery", "leaseRecoveryDone")}>{t("leaseRecovery")}</ActionButton>
            <ActionButton tone="ghost" onClick={() => room.runAutomation("/api/v0/workforce/cleanup", "cleanupDone")}>{t("cleanup")}</ActionButton>
          </div>
          {room.lastAutomationResult ? <details className="trace-block" open><summary>{t("lastAutomationResultLabel")}</summary><pre>{prettyJson(room.lastAutomationResult)}</pre></details> : <p className="panel-copy">{t("noAutomationResult")}</p>}
        </Panel>

        <Panel title={t("workerPool")} kicker={t("inventory")} wide>
          {workers ? (
            <div className="list-stack">
              {arrayOf(workers, "workers").map((worker) => (
                <div key={read(worker, "workerId")} className="list-card list-card--static">
                  <div className="list-card__head">
                    <div>
                      <p className="eyebrow">{read(worker, "workerId")}</p>
                      <strong>{read(worker, "status") || "UNKNOWN"}</strong>
                    </div>
                    <StatusBadge value={read(worker, "status") || "UNKNOWN"} />
                  </div>
                  <div className="pill-row">
                    {arrayOf(worker, "toolpackIds").length > 0 ? arrayOf(worker, "toolpackIds").map((toolpack) => <StatusBadge key={toolpack} value={toolpack} soft />) : <StatusBadge value="no-toolpack" soft />}
                  </div>
                  <div className="list-card__foot">
                    <span>{`${t("createdShort")} ${formatDateTime(read(worker, "createdAt"))}`}</span>
                    <span>{`${t("updatedShort")} ${formatDateTime(read(worker, "updatedAt"))}`}</span>
                  </div>
                </div>
              ))}
            </div>
          ) : <p className="panel-copy">{t("noWorkerList")}</p>}
        </Panel>
      </div>
    </section>
  );
}

function RuntimeProfileEditor({ title, profile, onChange }) {
  const { t } = useI18n();
  return (
    <div className="profile-editor">
      <p className="eyebrow">{title}</p>
      <div className="field-grid">
        <Field label={t("providerLabel")}><input value={profile.provider} onChange={(event) => onChange("provider", event.target.value)} /></Field>
        <Field label={t("frameworkLabel")}><input value={profile.framework} onChange={(event) => onChange("framework", event.target.value)} /></Field>
        <Field label={t("baseUrlLabel")}><input value={profile.baseUrl} onChange={(event) => onChange("baseUrl", event.target.value)} /></Field>
        <Field label={t("modelLabel")}><input value={profile.model} onChange={(event) => onChange("model", event.target.value)} /></Field>
        <Field label={t("timeoutMsLabel")}><input value={profile.timeoutMs} onChange={(event) => onChange("timeoutMs", event.target.value)} /></Field>
        <Field label={t("apiKeyLabel")}><input value={profile.apiKey} placeholder={profile.apiKeyMasked || t("keepExisting")} onChange={(event) => onChange("apiKey", event.target.value)} /></Field>
      </div>
    </div>
  );
}

function RuntimeProfileSummary({ title, profile }) {
  const { t } = useI18n();
  return (
    <div className="profile-summary">
      <p className="eyebrow">{title}</p>
      <div className="property-list">
        <PropertyRow label={t("providerLabel")} value={read(profile, "provider") || "-"} />
        <PropertyRow label={t("modelLabel")} value={read(profile, "model") || "-"} />
        <PropertyRow label={t("baseUrlLabel")} value={read(profile, "baseUrl") || "-"} />
        <PropertyRow label={t("apiKeyLabel")} value={read(profile, "apiKeyMasked") || "-"} />
      </div>
    </div>
  );
}

function buildPrimaryAction(progress, requirement, onNavigate, t) {
  const waitingUser = read(read(progress, "ticketCounts"), "waitingUser") || 0;
  if (waitingUser > 0) {
    return {
      label: t("primaryWaitingLabel"),
      description: t("primaryWaitingDescription", { count: waitingUser }),
      button: t("openInbox"),
      onClick: () => onNavigate("tickets"),
    };
  }
  if (!requirement || read(requirement, "status") !== "CONFIRMED") {
    return {
      label: requirement ? t("primaryRequirementPendingLabel") : t("primaryRequirementMissingLabel"),
      description: t("primaryRequirementDescription"),
      button: t("openRequirement"),
      onClick: () => onNavigate("requirement"),
    };
  }
  if (read(progress, "canCompleteSession")) {
    return {
      label: t("primaryDeliveryLabel"),
      description: t("primaryDeliveryDescription"),
      button: t("openDelivery"),
      onClick: () => onNavigate("delivery"),
    };
  }
  return {
    label: t("primaryExecutionLabel"),
    description: t("primaryExecutionDescription"),
    button: t("openExecution"),
    onClick: () => onNavigate("execution"),
  };
}

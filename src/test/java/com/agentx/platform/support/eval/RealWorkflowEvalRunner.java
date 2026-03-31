package com.agentx.platform.support.eval;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.ConfirmRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.RuntimeSupervisorSweep;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.evaluation.EvalEvidenceBundle;
import com.agentx.platform.runtime.evaluation.EvalReportArtifacts;
import com.agentx.platform.runtime.evaluation.WorkflowEvalCenter;
import com.agentx.platform.runtime.evaluation.WorkflowEvalTraceCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RealWorkflowEvalRunner {

    private final FixedCodingWorkflowUseCase workflowUseCase;
    private final CatalogStore catalogStore;
    private final com.agentx.platform.domain.execution.port.ExecutionStore executionStore;
    private final AgentRuntime agentRuntime;
    private final RuntimeSupervisorSweep runtimeSupervisorSweep;
    private final WorkflowEvalCenter workflowEvalCenter;
    private final WorkflowEvalTraceCollector workflowEvalTraceCollector;
    private final ObjectMapper objectMapper;
    private final Path repoRoot;
    private final Path artifactRoot;
    private final Path exportRoot;
    private final Path reviewBundleRoot;

    public RealWorkflowEvalRunner(
            FixedCodingWorkflowUseCase workflowUseCase,
            CatalogStore catalogStore,
            com.agentx.platform.domain.execution.port.ExecutionStore executionStore,
            AgentRuntime agentRuntime,
            RuntimeSupervisorSweep runtimeSupervisorSweep,
            WorkflowEvalCenter workflowEvalCenter,
            WorkflowEvalTraceCollector workflowEvalTraceCollector,
            ObjectMapper objectMapper,
            Path repoRoot,
            Path artifactRoot,
            Path exportRoot,
            Path reviewBundleRoot
    ) {
        this.workflowUseCase = workflowUseCase;
        this.catalogStore = catalogStore;
        this.executionStore = executionStore;
        this.agentRuntime = agentRuntime;
        this.runtimeSupervisorSweep = runtimeSupervisorSweep;
        this.workflowEvalCenter = workflowEvalCenter;
        this.workflowEvalTraceCollector = workflowEvalTraceCollector;
        this.objectMapper = objectMapper;
        this.repoRoot = repoRoot;
        this.artifactRoot = artifactRoot;
        this.exportRoot = exportRoot;
        this.reviewBundleRoot = reviewBundleRoot;
    }

    public RealWorkflowEvalRunResult runStrict(RealWorkflowEvalScenarioPackLoader.LoadedScenarioPack loadedScenario) throws IOException {
        RealWorkflowEvalScenarioPack scenarioPack = loadedScenario.pack();
        List<Map<String, Object>> steps = new ArrayList<>();
        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                scenarioPack.workflowTitle(),
                scenarioPack.requirementTitle(),
                scenarioPack.initialPrompt(),
                scenarioPack.profileId(),
                new ActorRef(ActorType.HUMAN, "real-eval-user"),
                false,
                scenarioPack.toWorkflowScenario()
        ));
        int scriptedHumanResponsesUsed = 0;
        boolean requirementConfirmed = false;
        String runnerStatus = "RUNNING";
        String stopReason = "workflow is still running";
        String terminalError = null;
        WorkflowRuntimeSnapshot snapshot = workflowUseCase.getRuntimeSnapshot(workflowRunId);

        try {
            alignAgentModels(scenarioPack.agentModelOverrides());
            while (true) {
                try {
                    snapshot = workflowUseCase.runUntilStable(workflowRunId);
                } catch (RuntimeException exception) {
                    FailureClassification failure = classifyFailure(exception);
                    runnerStatus = "ABORTED";
                    stopReason = failure.stopReason();
                    terminalError = failureSummary(exception);
                    steps.add(step(
                            failure.stepType(),
                            stopReason,
                            workflowRunId,
                            workflowUseCase.getRuntimeSnapshot(workflowRunId)
                    ));
                    if (scenarioPack.stopPolicy().terminateActiveRunsOnAbort()) {
                        terminateActiveRuns();
                    }
                    snapshot = workflowUseCase.getRuntimeSnapshot(workflowRunId);
                    break;
                }

                steps.add(step("STABLE_SNAPSHOT", "workflow reached a stable boundary", workflowRunId, snapshot));

                if (isTerminal(snapshot.workflowRun().status())) {
                    runnerStatus = snapshot.workflowRun().status() == WorkflowRunStatus.COMPLETED ? "COMPLETED" : "TERMINATED";
                    stopReason = "workflow reached terminal status " + snapshot.workflowRun().status().name();
                    break;
                }
                if (isStableAgentBlocked(snapshot)) {
                    runnerStatus = "ABORTED";
                    stopReason = "workflow paused with unresolved agent blocker ticket";
                    steps.add(step("AGENT_BLOCKED", stopReason, workflowRunId, snapshot));
                    break;
                }

                if (snapshot.workflowRun().status() != WorkflowRunStatus.WAITING_ON_HUMAN) {
                    runnerStatus = "ABORTED";
                    stopReason = "workflow stopped at unexpected stable status " + snapshot.workflowRun().status().name();
                    if (scenarioPack.stopPolicy().terminateActiveRunsOnAbort()) {
                        terminateActiveRuns();
                    }
                    snapshot = workflowUseCase.getRuntimeSnapshot(workflowRunId);
                    steps.add(step("UNEXPECTED_STATUS", stopReason, workflowRunId, snapshot));
                    break;
                }

                if (scenarioPack.autoConfirmRequirementDoc()
                        && !requirementConfirmed
                        && snapshot.requirementDoc().isPresent()
                        && snapshot.requirementDoc().orElseThrow().status() == RequirementStatus.IN_REVIEW) {
                    workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                            snapshot.requirementDoc().orElseThrow().docId(),
                            snapshot.requirementDoc().orElseThrow().currentVersion(),
                            new ActorRef(ActorType.HUMAN, "real-eval-user")
                    ));
                    requirementConfirmed = true;
                    steps.add(Map.of(
                            "stepType", "CONFIRM_REQUIREMENT_DOC",
                            "docId", snapshot.requirementDoc().orElseThrow().docId(),
                            "workflowRunId", workflowRunId,
                            "timestamp", LocalDateTime.now().toString()
                    ));
                    continue;
                }

                Optional<Ticket> openHumanTicket = firstOpenHumanTicket(snapshot);
                Optional<RealWorkflowEvalScenarioPack.ScriptedHumanResponse> scriptedResponse =
                        nextScriptedHumanResponse(openHumanTicket, scenarioPack, scriptedHumanResponsesUsed);
                if (openHumanTicket.isPresent() && scriptedResponse.isPresent()) {
                    Ticket ticket = openHumanTicket.orElseThrow();
                    RealWorkflowEvalScenarioPack.ScriptedHumanResponse response = scriptedResponse.orElseThrow();
                    workflowUseCase.answerTicket(new AnswerTicketCommand(
                            ticket.ticketId(),
                            response.answer(),
                            new ActorRef(ActorType.HUMAN, "real-eval-user")
                    ));
                    scriptedHumanResponsesUsed++;
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("stepType", "ANSWER_SCRIPTED_HUMAN_TICKET");
                    step.put("ticketId", ticket.ticketId());
                    step.put("originNodeId", ticket.originNodeId());
                    step.put("ticketType", ticket.type().name());
                    step.put("scriptIndex", scriptedHumanResponsesUsed);
                    step.put("workflowRunId", workflowRunId);
                    step.put("timestamp", LocalDateTime.now().toString());
                    if (!response.originNodeId().isBlank()) {
                        step.put("scriptOriginNodeId", response.originNodeId());
                    }
                    if (!response.ticketType().isBlank()) {
                        step.put("scriptTicketType", response.ticketType());
                    }
                    steps.add(step);
                    continue;
                }

                runnerStatus = "ABORTED";
                stopReason = unexpectedHumanWaitReason(snapshot, scenarioPack, scriptedHumanResponsesUsed, requirementConfirmed);
                steps.add(step("UNEXPECTED_HUMAN_WAIT", stopReason, workflowRunId, snapshot));
                if (scenarioPack.stopPolicy().terminateActiveRunsOnAbort()) {
                    terminateActiveRuns();
                    snapshot = workflowUseCase.getRuntimeSnapshot(workflowRunId);
                }
                break;
            }

            Path scenarioPackArtifact = artifactRoot.resolve("scenario-pack.json");
            writeString(scenarioPackArtifact, loadedScenario.rawJson());

            Map<String, Path> exportedSnapshots = exportWorkspaceSnapshots(snapshot);
            Path reviewBundle = buildReviewBundle(exportedSnapshots);
            Path workflowResultPath = artifactRoot.resolve("workflow-result.json");
            Map<String, Object> workflowResult = workflowResultMap(
                    loadedScenario,
                    workflowRunId,
                    runnerStatus,
                    stopReason,
                    terminalError,
                    snapshot,
                    steps,
                    reviewBundle,
                    exportedSnapshots
            );
            writeJson(workflowResultPath, workflowResult);

            Map<String, String> artifactRefs = new LinkedHashMap<>();
            artifactRefs.put("scenarioPack", scenarioPackArtifact.toString());
            artifactRefs.put("workflowResult", workflowResultPath.toString());
            artifactRefs.put("reviewBundle", reviewBundle.toString());
            exportedSnapshots.forEach((taskId, path) -> artifactRefs.put("exportedSnapshot:" + taskId, path.toString()));

            EvalReportArtifacts reportArtifacts = workflowEvalCenter.generateWorkflowReport(
                    scenarioPack.toEvalScenario(),
                    new EvalEvidenceBundle(
                            snapshot,
                            taskRunEventsByRun(snapshot),
                            workflowEvalTraceCollector.listContextArtifacts(workflowRunId),
                            Map.of(
                                    "workflowResult", workflowResult,
                                    "scenarioPack", objectMapper.readValue(loadedScenario.rawJson(), Map.class)
                            ),
                            artifactRefs
                    )
            );
            writeRunOverview(loadedScenario, workflowRunId, runnerStatus, stopReason, terminalError, reportArtifacts);
            return new RealWorkflowEvalRunResult(
                    workflowRunId,
                    runnerStatus,
                    stopReason,
                    terminalError,
                    snapshot,
                    workflowResultPath,
                    reportArtifacts
            );
        } finally {
            workflowEvalTraceCollector.clearWorkflow(workflowRunId);
        }
    }

    private void alignAgentModels(Map<String, String> agentModelOverrides) {
        for (Map.Entry<String, String> override : agentModelOverrides.entrySet()) {
            AgentDefinition current = catalogStore.findAgent(override.getKey())
                    .orElseThrow(() -> new IllegalStateException("agent definition not found: " + override.getKey()));
            if (current.model().equals(override.getValue())) {
                continue;
            }
            catalogStore.saveAgent(new AgentDefinition(
                    current.agentId(),
                    current.displayName(),
                    current.purpose(),
                    current.registrationSource(),
                    current.runtimeType(),
                    override.getValue(),
                    current.maxParallelRuns(),
                    current.architectSuggested(),
                    current.autoPoolEligible(),
                    current.manualRegistrationAllowed(),
                    current.enabled()
            ));
        }
    }

    private void terminateActiveRuns() {
        for (AgentPoolInstance instance : executionStore.listActiveAgentInstances()) {
            try {
                agentRuntime.terminate(instance);
            } catch (RuntimeException ignored) {
                // Best-effort stop before the supervisor reconciles the remaining evidence.
            }
        }
        runtimeSupervisorSweep.sweepOnce();
    }

    private boolean isTerminal(WorkflowRunStatus status) {
        return status == WorkflowRunStatus.COMPLETED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELED;
    }

    private Optional<Ticket> firstOpenHumanTicket(WorkflowRuntimeSnapshot snapshot) {
        return snapshot.tickets().stream()
                .filter(ticket -> ticket.status() == TicketStatus.OPEN && ticket.assignee().type() == ActorType.HUMAN)
                .findFirst();
    }

    private Optional<RealWorkflowEvalScenarioPack.ScriptedHumanResponse> nextScriptedHumanResponse(
            Optional<Ticket> openHumanTicket,
            RealWorkflowEvalScenarioPack scenarioPack,
            int scriptedHumanResponsesUsed
    ) {
        if (openHumanTicket.isEmpty() || scriptedHumanResponsesUsed >= scenarioPack.scriptedHumanResponseCount()) {
            return Optional.empty();
        }
        Ticket ticket = openHumanTicket.orElseThrow();
        RealWorkflowEvalScenarioPack.ScriptedHumanResponse response =
                scenarioPack.scriptedHumanResponses().get(scriptedHumanResponsesUsed);
        if (!response.originNodeId().isBlank()
                && !response.originNodeId().equalsIgnoreCase(String.valueOf(ticket.originNodeId()))) {
            return Optional.empty();
        }
        if (!response.ticketType().isBlank()
                && !response.ticketType().equalsIgnoreCase(ticket.type().name())) {
            return Optional.empty();
        }
        return Optional.of(response);
    }

    private boolean isStableAgentBlocked(WorkflowRuntimeSnapshot snapshot) {
        boolean hasOpenAgentBlocker = snapshot.tickets().stream()
                .anyMatch(ticket -> ticket.status() == TicketStatus.OPEN
                        && ticket.assignee().type() != ActorType.HUMAN
                        && ticket.blockingScope() != TicketBlockingScope.INFORMATIONAL);
        boolean hasActiveRun = snapshot.taskRuns().stream()
                .anyMatch(run -> run.status().name().equals("QUEUED") || run.status().name().equals("RUNNING"));
        return hasOpenAgentBlocker && !hasActiveRun;
    }

    private String unexpectedHumanWaitReason(
            WorkflowRuntimeSnapshot snapshot,
            RealWorkflowEvalScenarioPack scenarioPack,
            int scriptedHumanResponsesUsed,
            boolean requirementConfirmed
    ) {
        long answeredCount = scriptedHumanResponsesUsed + (requirementConfirmed ? 1 : 0);
        if (answeredCount >= scenarioPack.stopPolicy().maxHumanInteractions()) {
            return "workflow required more human interventions than the scenario pack allows";
        }
        Optional<Ticket> openHumanTicket = firstOpenHumanTicket(snapshot);
        if (openHumanTicket.isPresent() && scriptedHumanResponsesUsed >= scenarioPack.scriptedHumanResponseCount()) {
            return "workflow requested additional human input after scriptedHumanResponses were exhausted";
        }
        if (openHumanTicket.isPresent()) {
            Ticket ticket = openHumanTicket.orElseThrow();
            RealWorkflowEvalScenarioPack.ScriptedHumanResponse nextResponse =
                    scenarioPack.scriptedHumanResponses().get(scriptedHumanResponsesUsed);
            String expected = "{originNodeId="
                    + (nextResponse.originNodeId().isBlank() ? "*" : nextResponse.originNodeId())
                    + ", ticketType="
                    + (nextResponse.ticketType().isBlank() ? "*" : nextResponse.ticketType())
                    + "}";
            return "workflow paused for a human ticket that did not match the next scripted response: ticket origin="
                    + ticket.originNodeId()
                    + ", ticketType="
                    + ticket.type().name()
                    + ", expected="
                    + expected;
        }
        return "workflow paused for human input outside the scripted ticket/review path";
    }

    private Map<String, Object> step(
            String stepType,
            String body,
            String workflowRunId,
            WorkflowRuntimeSnapshot snapshot
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepType", stepType);
        data.put("body", body);
        data.put("workflowRunId", workflowRunId);
        data.put("workflowStatus", snapshot.workflowRun().status().name());
        data.put("timestamp", LocalDateTime.now().toString());
        data.put("openHumanTicketCount", snapshot.tickets().stream()
                .filter(ticket -> ticket.status() == TicketStatus.OPEN && ticket.assignee().type() == ActorType.HUMAN)
                .count());
        data.put("taskStatuses", snapshot.tasks().stream().map(task -> Map.of(
                "taskId", task.taskId(),
                "title", task.title(),
                "status", task.status().name()
        )).toList());
        return data;
    }

    private Map<String, List<TaskRunEvent>> taskRunEventsByRun(WorkflowRuntimeSnapshot snapshot) {
        Map<String, List<TaskRunEvent>> events = new LinkedHashMap<>();
        snapshot.taskRuns().forEach(run -> events.put(run.runId(), executionStore.listTaskRunEvents(run.runId())));
        return events;
    }

    private Map<String, Path> exportWorkspaceSnapshots(WorkflowRuntimeSnapshot snapshot) throws IOException {
        Map<String, String> taskTitles = new LinkedHashMap<>();
        for (WorkTask task : snapshot.tasks()) {
            taskTitles.put(task.taskId(), task.title());
        }
        Map<String, Path> exports = new LinkedHashMap<>();
        for (GitWorkspace workspace : snapshot.workspaces()) {
            if (workspace.headCommit() == null || workspace.headCommit().isBlank()) {
                continue;
            }
            String taskId = workspace.taskId();
            String title = taskTitles.getOrDefault(taskId, taskId);
            Path exportDirectory = exportRoot.resolve(sanitizeForFileName(taskId + "-" + title));
            exportCommit(workspace.headCommit(), exportDirectory);
            exports.put(taskId, exportDirectory);
        }
        return exports;
    }

    private Path buildReviewBundle(Map<String, Path> exportedSnapshots) throws IOException {
        Files.createDirectories(reviewBundleRoot);
        try (var existing = Files.walk(reviewBundleRoot)) {
            for (Path candidate : existing.sorted(Comparator.reverseOrder()).toList()) {
                if (!candidate.equals(reviewBundleRoot)) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
        for (Path exportedSnapshot : exportedSnapshots.values()) {
            copyTree(exportedSnapshot, reviewBundleRoot);
        }
        deleteMarkerFiles(reviewBundleRoot);
        return reviewBundleRoot;
    }

    private void exportCommit(String commit, Path exportDirectory) throws IOException {
        if (Files.exists(exportDirectory)) {
            try (var existing = Files.walk(exportDirectory)) {
                for (Path candidate : existing.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
        Files.createDirectories(exportDirectory);
        List<String> files = gitOutput(List.of("git", "ls-tree", "-r", "--name-only", commit)).lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        for (String file : files) {
            if (file.contains(".git")) {
                continue;
            }
            String content = gitOutput(List.of("git", "show", commit + ":" + file));
            Path target = exportDirectory.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        }
    }

    private void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        if (sourceRoot == null || Files.notExists(sourceRoot)) {
            return;
        }
        try (var stream = Files.walk(sourceRoot)) {
            for (Path candidate : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = sourceRoot.relativize(candidate);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(candidate)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(candidate, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteMarkerFiles(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path candidate : stream.toList()) {
                if (Files.isRegularFile(candidate) && candidate.getFileName().toString().startsWith(".agentx-")) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    private Map<String, Object> workflowResultMap(
            RealWorkflowEvalScenarioPackLoader.LoadedScenarioPack loadedScenario,
            String workflowRunId,
            String runnerStatus,
            String stopReason,
            String terminalError,
            WorkflowRuntimeSnapshot snapshot,
            List<Map<String, Object>> steps,
            Path reviewBundle,
            Map<String, Path> exportedSnapshots
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenarioId", loadedScenario.pack().scenarioId());
        result.put("profileId", loadedScenario.pack().profileId());
        result.put("scenarioPackSource", loadedScenario.sourceRef());
        result.put("workflowRunId", workflowRunId);
        result.put("runnerStatus", runnerStatus);
        result.put("stopReason", stopReason);
        result.put("terminalError", terminalError);
        result.put("workflowStatus", snapshot.workflowRun().status().name());
        result.put("stopPolicy", loadedScenario.pack().stopPolicy());
        snapshot.workflowProfile().ifPresent(profileRef -> {
            result.put("profileVersion", profileRef.version());
            result.put("profileDigest", profileRef.digest());
        });
        result.put("reviewBundle", reviewBundle.toString());
        result.put("stepHistory", steps);
        result.put("openTickets", snapshot.tickets().stream().map(ticket -> Map.of(
                "ticketId", ticket.ticketId(),
                "title", ticket.title(),
                "status", ticket.status().name(),
                "assigneeType", ticket.assignee().type().name(),
                "assigneeId", ticket.assignee().actorId(),
                "originNodeId", String.valueOf(ticket.originNodeId()),
                "taskId", String.valueOf(ticket.taskId())
        )).toList());
        result.put("nodeRuns", snapshot.nodeRuns().stream()
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt))
                .map(nodeRun -> Map.of(
                        "nodeRunId", nodeRun.nodeRunId(),
                        "nodeId", nodeRun.nodeId(),
                        "status", nodeRun.status().name(),
                        "startedAt", String.valueOf(nodeRun.startedAt()),
                        "finishedAt", String.valueOf(nodeRun.finishedAt())
                ))
                .toList());
        result.put("taskStatuses", snapshot.tasks().stream().map(task -> Map.of(
                "taskId", task.taskId(),
                "title", task.title(),
                "status", task.status().name()
        )).toList());
        result.put("taskRuns", snapshot.taskRuns().stream().map(run -> Map.of(
                "runId", run.runId(),
                "taskId", run.taskId(),
                "status", run.status().name()
        )).toList());
        Map<String, String> exportRefs = new LinkedHashMap<>();
        exportedSnapshots.forEach((taskId, path) -> exportRefs.put(taskId, path.toString()));
        result.put("exportedSnapshots", exportRefs);
        return result;
    }

    private void writeRunOverview(
            RealWorkflowEvalScenarioPackLoader.LoadedScenarioPack loadedScenario,
            String workflowRunId,
            String runnerStatus,
            String stopReason,
            String terminalError,
            EvalReportArtifacts reportArtifacts
    ) throws IOException {
        Path runRoot = artifactRoot.toAbsolutePath().normalize().getParent();
        if (runRoot == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# Real Workflow Eval Run").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 基本信息").append(System.lineSeparator());
        builder.append("- generatedAt: `").append(LocalDateTime.now()).append("`").append(System.lineSeparator());
        builder.append("- scenarioId: `").append(loadedScenario.pack().scenarioId()).append("`").append(System.lineSeparator());
        builder.append("- profileId: `").append(loadedScenario.pack().profileId()).append("`").append(System.lineSeparator());
        builder.append("- workflowTitle: ").append(loadedScenario.pack().workflowTitle()).append(System.lineSeparator());
        builder.append("- requirementTitle: ").append(loadedScenario.pack().requirementTitle()).append(System.lineSeparator());
        builder.append("- evaluationContent: `").append(loadedScenario.pack().initialPrompt()).append("`").append(System.lineSeparator());
        builder.append("- workflowRunId: `").append(workflowRunId).append("`").append(System.lineSeparator());
        builder.append("- runnerStatus: `").append(runnerStatus).append("`").append(System.lineSeparator());
        builder.append("- stopReason: ").append(stopReason).append(System.lineSeparator());
        if (terminalError != null && !terminalError.isBlank()) {
            builder.append("- terminalError: `").append(terminalError).append("`").append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        builder.append("## 关键文件索引").append(System.lineSeparator());
        builder.append("- runRoot: `").append(runRoot).append("`").append(System.lineSeparator());
        builder.append("- reportDirectory: `").append(reportArtifacts.outputDirectory()).append("`").append(System.lineSeparator());
        builder.append("- workflow-eval-report.md: `").append(reportArtifacts.markdownReportPath()).append("`").append(System.lineSeparator());
        builder.append("- raw-evidence.json: `").append(reportArtifacts.rawEvidencePath()).append("`").append(System.lineSeparator());
        builder.append("- scorecard.json: `").append(reportArtifacts.scorecardPath()).append("`").append(System.lineSeparator());
        builder.append("- profile-snapshot.json: `").append(reportArtifacts.profileSnapshotPath()).append("`").append(System.lineSeparator());
        builder.append("- workflow-result.json: `").append(artifactRoot.resolve("workflow-result.json").toAbsolutePath().normalize()).append("`")
                .append(System.lineSeparator());
        builder.append("- scenario-pack.json: `").append(artifactRoot.resolve("scenario-pack.json").toAbsolutePath().normalize()).append("`")
                .append(System.lineSeparator());
        builder.append("- review-bundle: `").append(reviewBundleRoot.toAbsolutePath().normalize()).append("`").append(System.lineSeparator());
        writeString(runRoot.resolve("README.md"), builder.toString());
    }

    private String gitOutput(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git command failed: " + String.join(" ", command) + System.lineSeparator() + stderr);
            }
            return stdout;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute git command: " + String.join(" ", command), exception);
        }
    }

    private void writeJson(Path path, Object payload) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload), StandardCharsets.UTF_8);
    }

    private void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String sanitizeForFileName(String rawValue) {
        return rawValue.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private String failureSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown runtime failure";
        }
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return throwable.getClass().getSimpleName() + ": " + message;
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null && !causeMessage.isBlank()) {
                return throwable.getClass().getSimpleName()
                        + " caused by "
                        + cause.getClass().getSimpleName()
                        + ": "
                        + causeMessage;
            }
            return throwable.getClass().getSimpleName() + " caused by " + cause.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName();
    }

    private FailureClassification classifyFailure(Throwable throwable) {
        String summary = failureSummary(throwable).toLowerCase();
        if (summary.contains("deepseek model call failed")
                || summary.contains("api-key must be configured")
                || summary.contains("timeout")
                || summary.contains("502")
                || summary.contains("503")
                || summary.contains("504")) {
            return new FailureClassification(
                    "PROVIDER_FAILURE",
                    "workflow terminated because the real model provider failed before reaching a stable boundary"
            );
        }
        if (summary.contains("failed to parse structured model response")
                || summary.contains("failed to parse json payload")
                || summary.contains("failed to parse payload")
                || summary.contains("missing json schema")) {
            return new FailureClassification(
                    "SCHEMA_FAILURE",
                    "workflow terminated because a real model response or runtime payload failed schema/JSON parsing"
            );
        }
        return new FailureClassification(
                "RUN_TIMEOUT",
                "workflow did not reach stable state before timeout"
        );
    }

    public record RealWorkflowEvalRunResult(
            String workflowRunId,
            String runnerStatus,
            String stopReason,
            String terminalError,
            WorkflowRuntimeSnapshot snapshot,
            Path workflowResultPath,
            EvalReportArtifacts reportArtifacts
    ) {
    }

    private record FailureClassification(
            String stepType,
            String stopReason
    ) {
    }
}

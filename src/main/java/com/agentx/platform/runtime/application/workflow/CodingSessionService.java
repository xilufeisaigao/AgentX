package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskContextSnapshotStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.agentkernel.coding.CodingConversationAgent;
import com.agentx.platform.runtime.agentkernel.coding.CodingAgentDecision;
import com.agentx.platform.runtime.agentkernel.coding.CodingDecisionType;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextCompilationProperties;
import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.tooling.ToolCall;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class CodingSessionService {

    private static final int MAX_TURNS = 8;

    private final CatalogStore catalogStore;
    private final PlanningStore planningStore;
    private final IntakeStore intakeStore;
    private final ExecutionStore executionStore;
    private final ContextCompilationCenter contextCompilationCenter;
    private final ContextCompilationProperties contextProperties;
    private final CodingConversationAgent codingConversationAgent;
    private final ToolExecutor toolExecutor;
    private final TaskExecutionContractBuilder taskExecutionContractBuilder;
    private final AgentRuntime agentRuntime;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final WorkflowScenarioResolver workflowScenarioResolver;
    private final StackProfileRegistry stackProfileRegistry;
    private final ObjectMapper objectMapper;

    public CodingSessionService(
            CatalogStore catalogStore,
            PlanningStore planningStore,
            IntakeStore intakeStore,
            ExecutionStore executionStore,
            ContextCompilationCenter contextCompilationCenter,
            ContextCompilationProperties contextProperties,
            CodingConversationAgent codingConversationAgent,
            ToolExecutor toolExecutor,
            TaskExecutionContractBuilder taskExecutionContractBuilder,
            AgentRuntime agentRuntime,
            WorkspaceProvisioner workspaceProvisioner,
            WorkflowScenarioResolver workflowScenarioResolver,
            StackProfileRegistry stackProfileRegistry,
            ObjectMapper objectMapper
    ) {
        this.catalogStore = catalogStore;
        this.planningStore = planningStore;
        this.intakeStore = intakeStore;
        this.executionStore = executionStore;
        this.contextCompilationCenter = contextCompilationCenter;
        this.contextProperties = contextProperties;
        this.codingConversationAgent = codingConversationAgent;
        this.toolExecutor = toolExecutor;
        this.taskExecutionContractBuilder = taskExecutionContractBuilder;
        this.agentRuntime = agentRuntime;
        this.workspaceProvisioner = workspaceProvisioner;
        this.workflowScenarioResolver = workflowScenarioResolver;
        this.stackProfileRegistry = stackProfileRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void advanceActiveRuns() {
        for (TaskRun run : executionStore.listActiveTaskRuns()) {
            if (run.status() != TaskRunStatus.RUNNING) {
                continue;
            }
            try {
                advanceRun(run);
            } catch (RuntimeException exception) {
                handleAdvanceFailure(run, exception);
            }
        }
    }

    private void advanceRun(TaskRun run) {
        WorkTask task = planningStore.findTask(run.taskId()).orElse(null);
        if (task == null || task.status() != WorkTaskStatus.IN_PROGRESS) {
            return;
        }
        AgentPoolInstance agentInstance = executionStore.findAgentInstance(run.agentInstanceId()).orElse(null);
        GitWorkspace workspace = executionStore.findWorkspaceByRun(run.runId()).orElse(null);
        if (agentInstance == null || workspace == null) {
            return;
        }
        List<TaskRunEvent> events = executionStore.listTaskRunEvents(run.runId());
        if (turnCount(events) >= MAX_TURNS) {
            failRun(run, agentInstance, task, workspace, "coding session exceeded turn budget");
            return;
        }
        TaskExecutionContract contract = taskExecutionContractBuilder.fromPayload(run.executionContractJson());
        String workflowRunId = workflowRunId(task.taskId(), run.runId(), agentInstance);
        CompiledContextPack contextPack = contextCompilationCenter.compile(new ContextCompilationRequest(
                ContextPackType.CODING,
                ContextScope.task(
                        workflowRunId,
                        task.taskId(),
                        run.runId(),
                        "coding",
                        java.nio.file.Path.of(workspace.worktreePath())
                ),
                "CODING_TURN"
        ));
        executionStore.saveTaskContextSnapshot(new TaskContextSnapshot(
                run.contextSnapshotId(),
                task.taskId(),
                run.runKind(),
                TaskContextSnapshotStatus.READY,
                "CODING_TURN",
                contextPack.sourceFingerprint(),
                contextPack.artifactRef(),
                null,
                LocalDateTime.now().plus(contextProperties.getRetention())
        ));

        AgentDefinition codingAgent = catalogStore.findAgent(agentInstance.agentId())
                .orElseThrow(() -> new IllegalStateException("coding agent definition not found: " + agentInstance.agentId()));
        StructuredModelResult<CodingAgentDecision> modelResult = codingConversationAgent.evaluate(
                codingAgent,
                contextPack,
                recentTurnSummary(events),
                workflowScenarioResolver.resolveProfileRef(workflowRunId).orElseGet(stackProfileRegistry::defaultProfileRef)
        );
        CodingAgentDecision decision = normalizeDecision(run.runId(), modelResult.value());
        if (decision.decisionType() == CodingDecisionType.ASK_BLOCKER) {
            createBlocker(task, run, workflowRunId, agentInstance.agentId(), decision);
            agentRuntime.terminate(agentInstance);
            executionStore.saveAgentInstance(disabled(agentInstance, Map.of("state", "blocked")));
            executionStore.saveTaskRun(withRunStatus(run, TaskRunStatus.CANCELED, now()));
            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
            executionStore.appendTaskRunEvent(new TaskRunEvent(
                    eventId("task-run"),
                    run.runId(),
                    "CODING_BLOCKER_REQUESTED",
                    decision.summary(),
                    decisionPayload(decision, modelResult, null, false)
            ));
            return;
        }

        // Tool calls are idempotent within a run via callId.
        // Replayed ticks must reuse prior evidence instead of re-running side effects like file writes or shell commands.
        PriorToolCallOutcome reusedOutcome = decision.decisionType() == CodingDecisionType.TOOL_CALL
                ? priorToolCallOutcome(events, decision.toolCall().callId())
                : null;
        ToolExecutor.ToolExecutionOutcome outcome = reusedOutcome == null
                ? (decision.decisionType() == CodingDecisionType.DELIVER
                ? toolExecutor.executeDeliveryPlan(task, run, agentInstance, workspace, contract)
                : toolExecutor.executeForRun(task, run, agentInstance, workspace, contract, decision.toolCall()))
                : reusedOutcome.toToolExecutionOutcome();
        executionStore.appendTaskRunEvent(new TaskRunEvent(
                eventId("task-run"),
                run.runId(),
                reusedOutcome == null ? "CODING_TURN_COMPLETED" : "CODING_TURN_REUSED",
                reusedOutcome == null ? outcome.body() : "reused tool execution evidence for " + decision.toolCall().callId(),
                decisionPayload(decision, modelResult, outcome.payload(), reusedOutcome != null)
        ));
        executionStore.saveTaskRun(refreshRun(run));
        executionStore.saveAgentInstance(refreshAgent(agentInstance));

        if (!outcome.terminal()) {
            return;
        }
        if (!outcome.succeeded()) {
            executionStore.appendTaskRunEvent(new TaskRunEvent(
                    eventId("task-run"),
                    run.runId(),
                    "CODING_DELIVERY_FAILED",
                    "deliver action failed",
                    outcome.payload()
            ));
            return;
        }

        agentRuntime.terminate(agentInstance);
        executionStore.saveAgentInstance(disabled(agentInstance, Map.of("state", "delivered")));
        executionStore.saveWorkspace(workspaceProvisioner.refreshHeadCommit(workspace));
        executionStore.saveTaskRun(withRunStatus(run, TaskRunStatus.SUCCEEDED, now()));
        planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.DELIVERED));
    }

    private void createBlocker(
            WorkTask task,
            TaskRun run,
            String workflowRunId,
            String codingAgentId,
            CodingAgentDecision decision
    ) {
        JsonPayload payload = jsonPayload(Map.of(
                "taskId", task.taskId(),
                "runId", run.runId(),
                "question", decision.blockerBody() == null ? decision.summary() : decision.blockerBody()
        ));
        Ticket ticket = new Ticket(
                "ticket-blocker-" + shortToken(task.taskId(), run.runId()),
                workflowRunId,
                TicketType.CLARIFICATION,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                decision.blockerTitle() == null ? "编码任务需要补充事实" : decision.blockerTitle(),
                new ActorRef(ActorType.AGENT, codingAgentId),
                new ActorRef(ActorType.AGENT, "architect-agent"),
                "coding",
                null,
                null,
                task.taskId(),
                payload
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "TASK_BLOCKER_CREATED",
                new ActorRef(ActorType.AGENT, codingAgentId),
                decision.summary(),
                payload
        ));
    }

    private int turnCount(List<TaskRunEvent> events) {
        return (int) events.stream()
                .filter(event -> event.eventType().startsWith("CODING_"))
                .count();
    }

    private String recentTurnSummary(List<TaskRunEvent> events) {
        List<TaskRunEvent> codingEvents = events.stream()
                .filter(event -> event.eventType().startsWith("CODING_"))
                .toList();
        return codingEvents.stream()
                .skip(Math.max(0, codingEvents.size() - 4L))
                .map(this::summarizeCodingEvent)
                .reduce((left, right) -> left + " | " + right)
                .orElse("no prior turns");
    }

    private String summarizeCodingEvent(TaskRunEvent event) {
        if (event.dataJson() == null) {
            return event.eventType() + ": " + event.body();
        }
        Map<String, Object> payload = payloadMap(event.dataJson());
        Map<String, Object> decision = mapValue(payload.get("decision"));
        Map<String, Object> toolCall = mapValue(decision.get("toolCall"));
        Map<String, Object> toolPayload = mapValue(payload.get("toolPayload"));
        String decisionType = stringValue(decision.get("decisionType"));
        if ("TOOL_CALL".equals(decisionType) && !toolCall.isEmpty()) {
            String toolId = stringValue(toolCall.get("toolId"));
            String operation = stringValue(toolCall.get("operation"));
            String arguments = summarizeMap(mapValue(toolCall.get("arguments")));
            String result = summarizeToolPayload(toolPayload);
            return "%s: %s.%s args=%s result=%s".formatted(
                    event.eventType(),
                    toolId,
                    operation,
                    arguments,
                    result
            );
        }
        return "%s: %s".formatted(event.eventType(), event.body());
    }

    private String summarizeToolPayload(Map<String, Object> toolPayload) {
        if (toolPayload.isEmpty()) {
            return "(no tool payload)";
        }
        String body = stringValue(toolPayload.get("body"));
        if (body != null && !body.isBlank()) {
            return truncate(body, 240);
        }
        return summarizeMap(toolPayload);
    }

    private String summarizeMap(Map<String, Object> values) {
        if (values.isEmpty()) {
            return "{}";
        }
        return values.entrySet().stream()
                .limit(6)
                .map(entry -> entry.getKey() + "=" + truncate(String.valueOf(entry.getValue()), 80))
                .reduce((left, right) -> left + ", " + right)
                .map(summary -> "{" + summary + "}")
                .orElse("{}");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private void failRun(TaskRun run, AgentPoolInstance agentInstance, WorkTask task, GitWorkspace workspace, String reason) {
        agentRuntime.terminate(agentInstance);
        executionStore.saveAgentInstance(disabled(agentInstance, Map.of("state", "failed", "reason", reason)));
        executionStore.saveTaskRun(withRunStatus(run, TaskRunStatus.FAILED, now()));
        if (workspace != null) {
            executionStore.saveWorkspace(workspaceProvisioner.cleanup(withWorkspaceFailure(workspace)));
        }
        planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.READY));
        executionStore.appendTaskRunEvent(new TaskRunEvent(
                eventId("task-run"),
                run.runId(),
                "CODING_TURN_BUDGET_EXCEEDED",
                reason,
                jsonPayload(Map.of("reason", reason))
        ));
    }

    private void handleAdvanceFailure(TaskRun run, RuntimeException exception) {
        WorkTask task = planningStore.findTask(run.taskId()).orElse(null);
        AgentPoolInstance agentInstance = executionStore.findAgentInstance(run.agentInstanceId()).orElse(null);
        GitWorkspace workspace = executionStore.findWorkspaceByRun(run.runId()).orElse(null);
        String reason = failureSummary(exception);
        if (agentInstance != null) {
            try {
                agentRuntime.terminate(agentInstance);
            } catch (RuntimeException ignored) {
                // Best effort shutdown; the evidence below is the source of truth for the failed attempt.
            }
            executionStore.saveAgentInstance(disabled(agentInstance, Map.of("state", "failed", "reason", reason)));
        }
        executionStore.saveTaskRun(withRunStatus(run, TaskRunStatus.FAILED, now()));
        executionStore.appendTaskRunEvent(new TaskRunEvent(
                eventId("task-run"),
                run.runId(),
                "CODING_TURN_FAILED",
                reason,
                jsonPayload(Map.of("reason", reason))
        ));
        if (workspace != null) {
            executionStore.saveWorkspace(workspaceProvisioner.cleanup(withWorkspaceFailure(workspace)));
        }
        if (task == null) {
            return;
        }
        planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
        createRuntimeAlertTicket(workflowRunId(task.taskId(), run.runId(), agentInstance), task, run.runId(), reason);
    }

    private TaskRun refreshRun(TaskRun run) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                run.status(),
                run.runKind(),
                run.contextSnapshotId(),
                now().plusSeconds(20),
                now(),
                run.startedAt(),
                run.finishedAt(),
                run.executionContractJson()
        );
    }

    private TaskRun withRunStatus(TaskRun run, TaskRunStatus status, LocalDateTime finishedAt) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                status,
                run.runKind(),
                run.contextSnapshotId(),
                now().plusSeconds(20),
                now(),
                run.startedAt(),
                finishedAt,
                run.executionContractJson()
        );
    }

    private AgentPoolInstance refreshAgent(AgentPoolInstance agentInstance) {
        return new AgentPoolInstance(
                agentInstance.agentInstanceId(),
                agentInstance.agentId(),
                agentInstance.runtimeType(),
                agentInstance.status(),
                agentInstance.launchMode(),
                agentInstance.currentWorkflowRunId(),
                now().plusSeconds(20),
                now(),
                agentInstance.endpointRef(),
                agentInstance.runtimeMetadataJson()
        );
    }

    private AgentPoolInstance disabled(AgentPoolInstance agentInstance, Map<String, Object> additions) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (agentInstance.runtimeMetadataJson() != null) {
            try {
                metadata.putAll(objectMapper.readValue(agentInstance.runtimeMetadataJson().json(), Map.class));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("failed to parse agent runtime metadata", exception);
            }
        }
        metadata.putAll(additions);
        return new AgentPoolInstance(
                agentInstance.agentInstanceId(),
                agentInstance.agentId(),
                agentInstance.runtimeType(),
                AgentPoolStatus.DISABLED,
                agentInstance.launchMode(),
                agentInstance.currentWorkflowRunId(),
                now(),
                now(),
                agentInstance.endpointRef(),
                jsonPayload(metadata)
        );
    }

    private WorkTask withTaskStatus(WorkTask task, WorkTaskStatus status) {
        return new WorkTask(
                task.taskId(),
                task.moduleId(),
                task.title(),
                task.objective(),
                task.taskTemplateId(),
                status,
                task.writeScopes(),
                task.originTicketId(),
                task.createdBy()
        );
    }

    private JsonPayload decisionPayload(
            CodingAgentDecision decision,
            StructuredModelResult<CodingAgentDecision> modelResult,
            JsonPayload toolPayload,
            boolean reused
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision);
        payload.put("modelProvider", modelResult.provider());
        payload.put("modelName", modelResult.model());
        payload.put("rawResponse", modelResult.rawResponse());
        payload.put("toolCallReused", reused);
        if (toolPayload != null) {
            payload.put("toolPayload", payloadMap(toolPayload));
        }
        return jsonPayload(payload);
    }

    private CodingAgentDecision normalizeDecision(String runId, CodingAgentDecision decision) {
        if (decision.decisionType() != CodingDecisionType.TOOL_CALL) {
            return decision;
        }
        ToolCall normalizedToolCall = toolExecutor.normalizeForRun(runId, decision.toolCall());
        return new CodingAgentDecision(
                decision.decisionType(),
                normalizedToolCall,
                decision.blockerTitle(),
                decision.blockerBody(),
                decision.summary()
        );
    }

    private PriorToolCallOutcome priorToolCallOutcome(List<TaskRunEvent> events, String callId) {
        for (int index = events.size() - 1; index >= 0; index--) {
            TaskRunEvent event = events.get(index);
            if (!event.eventType().equals("CODING_TURN_COMPLETED") && !event.eventType().equals("CODING_TURN_REUSED")) {
                continue;
            }
            if (event.dataJson() == null) {
                continue;
            }
            Map<String, Object> payload = payloadMap(event.dataJson());
            Map<String, Object> decision = mapValue(payload.get("decision"));
            if (!Objects.equals("TOOL_CALL", String.valueOf(decision.get("decisionType")))) {
                continue;
            }
            Map<String, Object> toolCall = mapValue(decision.get("toolCall"));
            if (!Objects.equals(callId, stringValue(toolCall.get("callId")))) {
                continue;
            }
            Map<String, Object> toolPayload = mapValue(payload.get("toolPayload"));
            if (toolPayload.isEmpty()) {
                continue;
            }
            return new PriorToolCallOutcome(
                    booleanValue(toolPayload.get("terminal")),
                    booleanValue(toolPayload.get("succeeded")),
                    jsonPayload(toolPayload),
                    event.body()
            );
        }
        return null;
    }

    private Map<String, Object> payloadMap(JsonPayload payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload.json(), Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to parse json payload", exception);
        }
    }

    private Map<String, Object> mapValue(Object rawValue) {
        if (rawValue == null) {
            return Map.of();
        }
        return objectMapper.convertValue(rawValue, Map.class);
    }

    private String stringValue(Object rawValue) {
        return rawValue == null ? null : String.valueOf(rawValue);
    }

    private boolean booleanValue(Object rawValue) {
        if (rawValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return rawValue != null && Boolean.parseBoolean(String.valueOf(rawValue));
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize json payload", exception);
        }
    }

    private String workflowRunId(String taskId, String runId, AgentPoolInstance agentInstance) {
        if (agentInstance != null && agentInstance.currentWorkflowRunId() != null && !agentInstance.currentWorkflowRunId().isBlank()) {
            return agentInstance.currentWorkflowRunId();
        }
        return planningStore.findWorkflowRunIdByTask(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "workflow run not found for task %s (runId=%s, agentInstanceId=%s)".formatted(
                                taskId,
                                runId,
                                agentInstance == null ? "null" : agentInstance.agentInstanceId()
                        )
                ));
    }

    private GitWorkspace withWorkspaceFailure(GitWorkspace workspace) {
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                GitWorkspaceStatus.FAILED,
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                workspace.mergeCommit(),
                workspace.cleanupStatus()
        );
    }

    private void createRuntimeAlertTicket(String workflowRunId, WorkTask task, String runId, String reason) {
        JsonPayload payload = jsonPayload(Map.of(
                "taskId", task.taskId(),
                "runId", runId,
                "reason", reason
        ));
        Ticket ticket = new Ticket(
                "ticket-runtime-" + shortToken(task.taskId(), runId, "coding-runtime"),
                workflowRunId,
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "编码运行失败需要架构代理处理",
                new ActorRef(ActorType.SYSTEM, "coding-session"),
                new ActorRef(ActorType.AGENT, "architect-agent"),
                "coding",
                null,
                null,
                task.taskId(),
                payload
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "RUNTIME_ALERT_CREATED",
                new ActorRef(ActorType.SYSTEM, "coding-session"),
                reason,
                payload
        ));
    }

    private String failureSummary(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getMessage();
        }
        return root.getClass().getSimpleName() + ": " + (message == null ? "unknown coding failure" : message);
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private String eventId(String prefix) {
        return prefix + "-event-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String shortToken(String... values) {
        return UUID.nameUUIDFromBytes(String.join("|", values).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 24);
    }

    private record PriorToolCallOutcome(
            boolean terminal,
            boolean succeeded,
            JsonPayload payload,
            String body
    ) {

        private ToolExecutor.ToolExecutionOutcome toToolExecutionOutcome() {
            return new ToolExecutor.ToolExecutionOutcome(terminal, succeeded, body, payload);
        }
    }
}

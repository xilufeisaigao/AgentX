package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.RunKind;
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
import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.AgentRuntimeHandle;
import com.agentx.platform.runtime.agentruntime.ContainerLaunchSpec;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextCompilationProperties;
import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.agentx.platform.runtime.workspace.git.GitWorktreeContainerBindings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class TaskDispatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String ARCHITECT_AGENT_ID = "architect-agent";

    private final CatalogStore catalogStore;
    private final PlanningStore planningStore;
    private final IntakeStore intakeStore;
    private final ExecutionStore executionStore;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final AgentRuntime agentRuntime;
    private final TaskExecutionContractBuilder contractBuilder;
    private final ContextCompilationCenter contextCompilationCenter;
    private final ContextCompilationProperties contextCompilationProperties;
    private final WorkflowScenarioResolver scenarioResolver;
    private final RuntimeInfrastructureProperties runtimeProperties;
    private final ObjectMapper objectMapper;

    public TaskDispatcher(
            CatalogStore catalogStore,
            PlanningStore planningStore,
            IntakeStore intakeStore,
            ExecutionStore executionStore,
            WorkspaceProvisioner workspaceProvisioner,
            AgentRuntime agentRuntime,
            TaskExecutionContractBuilder contractBuilder,
            ContextCompilationCenter contextCompilationCenter,
            ContextCompilationProperties contextCompilationProperties,
            WorkflowScenarioResolver scenarioResolver,
            RuntimeInfrastructureProperties runtimeProperties,
            ObjectMapper objectMapper
    ) {
        this.catalogStore = catalogStore;
        this.planningStore = planningStore;
        this.intakeStore = intakeStore;
        this.executionStore = executionStore;
        this.workspaceProvisioner = workspaceProvisioner;
        this.agentRuntime = agentRuntime;
        this.contractBuilder = contractBuilder;
        this.contextCompilationCenter = contextCompilationCenter;
        this.contextCompilationProperties = contextCompilationProperties;
        this.scenarioResolver = scenarioResolver;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<DispatchDecision> dispatchReadyTasks() {
        List<DispatchDecision> decisions = new ArrayList<>();
        for (String taskId : planningStore.claimReadyTaskIdsForDispatch(runtimeProperties.getDispatchBatchSize())) {
            WorkTask task = planningStore.findTask(taskId).orElse(null);
            if (task == null) {
                decisions.add(new DispatchDecision(taskId, false, "task disappeared before dispatch", null));
                continue;
            }
            decisions.add(dispatchTask(task));
        }
        return decisions;
    }

    private DispatchDecision dispatchTask(WorkTask task) {
        String workflowRunId = planningStore.findWorkflowRunIdByTask(task.taskId())
                .orElseThrow(() -> new IllegalStateException("missing workflow run for task " + task.taskId()));
        if (!dependenciesSatisfied(task.taskId())) {
            return new DispatchDecision(task.taskId(), false, "dependencies not satisfied", null);
        }
        if (intakeStore.hasOpenTaskBlocker(task.taskId())) {
            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
            return new DispatchDecision(task.taskId(), false, "task has unresolved blockers", null);
        }
        if (executionStore.listTaskRuns(task.taskId()).stream().anyMatch(this::isActiveRun)) {
            return new DispatchDecision(task.taskId(), false, "task already has an active run", null);
        }

        List<TaskCapabilityRequirement> capabilityRequirements = planningStore.listCapabilityRequirements(task.taskId());
        if (capabilityRequirements.isEmpty()) {
            return new DispatchDecision(task.taskId(), false, "task has no capability requirement", null);
        }
        AgentDefinition selectedAgent = selectAgent(capabilityRequirements.get(0).capabilityPackId());
        WorkflowScenario scenario = scenarioResolver.resolve(workflowRunId);
        int attemptNumber = executionStore.listTaskRuns(task.taskId()).size() + 1;
        TaskContextSnapshot snapshot = buildSnapshot(workflowRunId, task, attemptNumber);
        executionStore.saveTaskContextSnapshot(snapshot);

        if (requiresDeterministicClarification(task, workflowRunId, scenario, attemptNumber)) {
            return createClarificationDecision(task, workflowRunId, snapshot, selectedAgent, attemptNumber);
        }

        TaskExecutionContract executionContract = contractBuilder.build(
                workflowRunId,
                task,
                capabilityRequirements,
                attemptNumber,
                scenario
        );
        GitWorkspace workspace = null;
        AgentPoolInstance provisioningInstance = null;
        TaskRun queuedRun = null;
        boolean agentInstanceSaved = false;
        boolean runSaved = false;
        try {
            queuedRun = queuedRun(task, snapshot, executionContract, attemptNumber);
            workspace = workspaceProvisioner.allocate(
                    workflowRunId,
                    task,
                    queuedRun,
                    workspaceBaseRevision(task)
            );

            provisioningInstance = provisioningInstance(task, workflowRunId, selectedAgent, attemptNumber);
            executionStore.saveAgentInstance(provisioningInstance);
            agentInstanceSaved = true;
            executionStore.saveTaskRun(queuedRun);
            runSaved = true;
            executionStore.saveWorkspace(workspace);
            executionStore.appendTaskRunEvent(new TaskRunEvent(
                    eventId("task-run"),
                    queuedRun.runId(),
                    "RUN_CREATED",
                    "task run claimed by central dispatcher",
                    jsonPayload(Map.of(
                            "taskId", task.taskId(),
                            "workflowRunId", workflowRunId,
                            "attempt", attemptNumber
                    ))
            ));

            AgentRuntimeHandle runtimeHandle = agentRuntime.launch(toLaunchSpec(queuedRun, executionContract, workspace));
            executionStore.saveAgentInstance(new AgentPoolInstance(
                    provisioningInstance.agentInstanceId(),
                    provisioningInstance.agentId(),
                    provisioningInstance.runtimeType(),
                    AgentPoolStatus.READY,
                    provisioningInstance.launchMode(),
                    provisioningInstance.currentWorkflowRunId(),
                    leaseUntil(),
                    now(),
                    runtimeHandle.endpointRef(),
                    runtimeHandle.runtimeMetadataJson()
            ));
            executionStore.saveTaskRun(withRunStatus(queuedRun, TaskRunStatus.RUNNING, null));
            executionStore.appendTaskRunEvent(new TaskRunEvent(
                    eventId("task-run"),
                    queuedRun.runId(),
                    "RUN_STARTED",
                    "docker task container has been launched",
                    jsonPayload(Map.of(
                            "taskId", task.taskId(),
                            "workflowRunId", workflowRunId,
                            "agentInstanceId", provisioningInstance.agentInstanceId()
                    ))
            ));
            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.IN_PROGRESS));
            return new DispatchDecision(task.taskId(), true, "task dispatched", queuedRun.runId());
        } catch (RuntimeException exception) {
            if (agentInstanceSaved && provisioningInstance != null) {
                String failureSummary = failureSummary(exception);
                executionStore.saveAgentInstance(new AgentPoolInstance(
                        provisioningInstance.agentInstanceId(),
                        provisioningInstance.agentId(),
                        provisioningInstance.runtimeType(),
                        AgentPoolStatus.DISABLED,
                        provisioningInstance.launchMode(),
                        provisioningInstance.currentWorkflowRunId(),
                        now(),
                        now(),
                        provisioningInstance.endpointRef(),
                        mergeJson(provisioningInstance.runtimeMetadataJson(), mapWithNullableEntry("launchError", failureSummary))
                ));
            }
            if (runSaved && queuedRun != null) {
                String failureSummary = failureSummary(exception);
                executionStore.saveTaskRun(withRunStatus(queuedRun, TaskRunStatus.FAILED, now()));
                executionStore.appendTaskRunEvent(new TaskRunEvent(
                        eventId("task-run"),
                        queuedRun.runId(),
                        "RUN_FAILED",
                        failureSummary,
                        jsonPayload(Map.of(
                                "taskId", task.taskId(),
                                "workflowRunId", workflowRunId,
                                "phase", "launch"
                        ))
                ));
            }
            if (workspace != null) {
                GitWorkspace cleanedWorkspace = workspaceProvisioner.cleanup(withWorkspaceFailure(workspace));
                if (runSaved) {
                    executionStore.saveWorkspace(cleanedWorkspace);
                }
            }
            if (attemptNumber >= runtimeProperties.getMaxRunAttempts()) {
                planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
                createRuntimeAlertTicket(
                        workflowRunId,
                        task,
                        queuedRun == null ? null : queuedRun.runId(),
                        failureSummary(exception)
                );
            } else {
                planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.READY));
            }
            return new DispatchDecision(
                    task.taskId(),
                    false,
                    failureSummary(exception),
                    queuedRun == null ? null : queuedRun.runId()
            );
        }
    }

    private DispatchDecision createClarificationDecision(
            WorkTask task,
            String workflowRunId,
            TaskContextSnapshot snapshot,
            AgentDefinition selectedAgent,
            int attemptNumber
    ) {
        String agentInstanceId = agentInstanceId(task.taskId(), attemptNumber);
        executionStore.saveAgentInstance(new AgentPoolInstance(
                agentInstanceId,
                selectedAgent.agentId(),
                selectedAgent.runtimeType(),
                AgentPoolStatus.DISABLED,
                "TASK_RUN_CONTAINER",
                workflowRunId,
                now(),
                now(),
                null,
                jsonPayload(Map.of(
                        "taskId", task.taskId(),
                        "workflowRunId", workflowRunId,
                        "phase", "clarification"
                ))
        ));
        TaskRun canceledRun = new TaskRun(
                runId(task.taskId(), attemptNumber),
                task.taskId(),
                agentInstanceId,
                TaskRunStatus.CANCELED,
                RunKind.IMPL,
                snapshot.snapshotId(),
                now(),
                now(),
                now(),
                now(),
                JsonPayload.emptyObject()
        );
        executionStore.saveTaskRun(canceledRun);
        executionStore.appendTaskRunEvent(new TaskRunEvent(
                eventId("task-run"),
                canceledRun.runId(),
                "NEED_CLARIFICATION",
                "deterministic runtime requested clarification before container launch",
                jsonPayload(Map.of(
                        "taskId", task.taskId(),
                        "runId", canceledRun.runId(),
                        "workflowRunId", workflowRunId
                ))
        ));
        planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
        createClarificationTicket(workflowRunId, task, canceledRun.runId());
        return new DispatchDecision(task.taskId(), false, "clarification required", canceledRun.runId());
    }

    private AgentDefinition selectAgent(String capabilityPackId) {
        return catalogStore.listAgentsByCapability(capabilityPackId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no enabled agent found for capability " + capabilityPackId));
    }

    private ContainerLaunchSpec toLaunchSpec(TaskRun taskRun, TaskExecutionContract executionContract, GitWorkspace workspace) {
        GitWorktreeContainerBindings.GitContainerBinding gitBinding = GitWorktreeContainerBindings.forWorktree(
                Path.of(workspace.worktreePath()),
                executionContract.workingDirectory(),
                false,
                false
        );
        Map<String, String> environment = new LinkedHashMap<>(executionContract.environment());
        environment.putAll(gitBinding.environment());
        return new ContainerLaunchSpec(
                "agentx-" + shortToken(taskRun.runId(), workspace.workspaceId()),
                executionContract.image(),
                executionContract.workingDirectory(),
                executionContract.command(),
                gitBinding.mounts(),
                environment,
                runtimeProperties.getLeaseTtl().plusSeconds(executionContract.timeoutSeconds())
        );
    }

    private TaskRun queuedRun(
            WorkTask task,
            TaskContextSnapshot snapshot,
            TaskExecutionContract executionContract,
            int attemptNumber
    ) {
        return new TaskRun(
                runId(task.taskId(), attemptNumber),
                task.taskId(),
                agentInstanceId(task.taskId(), attemptNumber),
                TaskRunStatus.QUEUED,
                RunKind.IMPL,
                snapshot.snapshotId(),
                leaseUntil(),
                now(),
                now(),
                null,
                contractBuilder.toPayload(executionContract)
        );
    }

    private AgentPoolInstance provisioningInstance(
            WorkTask task,
            String workflowRunId,
            AgentDefinition selectedAgent,
            int attemptNumber
    ) {
        return new AgentPoolInstance(
                agentInstanceId(task.taskId(), attemptNumber),
                selectedAgent.agentId(),
                "docker",
                AgentPoolStatus.PROVISIONING,
                "TASK_RUN_CONTAINER",
                workflowRunId,
                leaseUntil(),
                now(),
                null,
                jsonPayload(Map.of(
                        "taskId", task.taskId(),
                        "workflowRunId", workflowRunId,
                        "phase", "provisioning"
                ))
        );
    }

    private TaskContextSnapshot buildSnapshot(String workflowRunId, WorkTask task, int attemptNumber) {
        String runId = runId(task.taskId(), attemptNumber);
        CompiledContextPack contextPack = contextCompilationCenter.compile(new ContextCompilationRequest(
                ContextPackType.CODING,
                ContextScope.task(workflowRunId, task.taskId(), runId, "coding", null),
                attemptNumber == 1 ? "TASK_READY" : "TASK_RETRY"
        ));
        return new TaskContextSnapshot(
                snapshotId(task.taskId(), attemptNumber),
                task.taskId(),
                RunKind.IMPL,
                TaskContextSnapshotStatus.READY,
                attemptNumber == 1 ? "TASK_READY" : "TASK_RETRY",
                contextPack.sourceFingerprint(),
                contextPack.artifactRef(),
                "runtime://skill/" + workflowRunId + "/" + task.taskId() + "/" + attemptNumber,
                now().plus(contextCompilationProperties.getRetention())
        );
    }

    private boolean dependenciesSatisfied(String taskId) {
        for (TaskDependency dependency : planningStore.listDependenciesForTask(taskId)) {
            WorkTask upstreamTask = planningStore.findTask(dependency.dependsOnTaskId())
                    .orElseThrow(() -> new IllegalStateException("missing upstream task " + dependency.dependsOnTaskId()));
            if (upstreamTask.status() != dependency.requiredUpstreamStatus()) {
                return false;
            }
        }
        return true;
    }

    private boolean requiresDeterministicClarification(
            WorkTask task,
            String workflowRunId,
            WorkflowScenario scenario,
            int attemptNumber
    ) {
        if (!scenario.requireHumanClarification() || attemptNumber != 1) {
            return false;
        }
        return intakeStore.listTicketsForWorkflow(workflowRunId).stream()
                .filter(ticket -> ticket.blockingScope() == TicketBlockingScope.TASK_BLOCKING)
                .map(ticket -> ticket.taskId() != null ? ticket.taskId() : payloadValue(ticket.payloadJson(), "taskId"))
                .noneMatch(task.taskId()::equals);
    }

    private void createClarificationTicket(String workflowRunId, WorkTask task, String runId) {
        JsonPayload payload = jsonPayload(Map.of(
                "taskId", task.taskId(),
                "runId", runId,
                "question", "Need deterministic clarification before execution can continue"
        ));
        Ticket ticket = new Ticket(
                "ticket-" + shortToken(task.taskId(), runId),
                workflowRunId,
                TicketType.CLARIFICATION,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "补齐任务执行所需澄清",
                new ActorRef(ActorType.SYSTEM, "runtime-dispatcher"),
                new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
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
                "CLARIFICATION_REQUESTED",
                new ActorRef(ActorType.SYSTEM, "runtime-dispatcher"),
                "dispatcher created a task-level clarification before launch",
                payload
        ));
    }

    private void createRuntimeAlertTicket(String workflowRunId, WorkTask task, String runId, String reason) {
        Map<String, Object> payloadData = new LinkedHashMap<>();
        payloadData.put("taskId", task.taskId());
        if (runId != null) {
            payloadData.put("runId", runId);
        }
        if (reason != null) {
            payloadData.put("reason", reason);
        }
        JsonPayload payload = jsonPayload(payloadData);
        Ticket ticket = new Ticket(
                "ticket-runtime-" + shortToken(task.taskId(), String.valueOf(runId)),
                workflowRunId,
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "运行失败需要架构代理处理",
                new ActorRef(ActorType.SYSTEM, "runtime-supervisor"),
                new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
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
                new ActorRef(ActorType.SYSTEM, "runtime-supervisor"),
                reason,
                payload
        ));
    }

    private boolean isActiveRun(TaskRun run) {
        return run.status() == TaskRunStatus.QUEUED || run.status() == TaskRunStatus.RUNNING;
    }

    private String workspaceBaseRevision(WorkTask task) {
        // Downstream tasks should see their satisfied dependency outputs, and verify-driven rework should
        // continue from the last accepted delivery candidate instead of replaying from the repo baseline.
        String dependencyBaseRevision = planningStore.listDependenciesForTask(task.taskId()).stream()
                .map(TaskDependency::dependsOnTaskId)
                .map(this::latestMergedCommitForTask)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (dependencyBaseRevision != null) {
            return dependencyBaseRevision;
        }
        String taskReworkBaseRevision = latestMergedCommitForTask(task.taskId());
        return taskReworkBaseRevision == null ? runtimeProperties.getBaseBranch() : taskReworkBaseRevision;
    }

    private String latestMergedCommitForTask(String taskId) {
        return executionStore.listTaskRuns(taskId).stream()
                .filter(run -> run.status() == TaskRunStatus.SUCCEEDED)
                .sorted(Comparator.comparing(TaskRun::startedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(TaskRun::runId)
                .map(runId -> executionStore.findWorkspaceByRun(runId).orElse(null))
                .filter(Objects::nonNull)
                .map(GitWorkspace::mergeCommit)
                .filter(commit -> commit != null && !commit.isBlank())
                .findFirst()
                .orElse(null);
    }

    private TaskRun withRunStatus(TaskRun run, TaskRunStatus status, LocalDateTime finishedAt) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                status,
                run.runKind(),
                run.contextSnapshotId(),
                leaseUntil(),
                now(),
                run.startedAt(),
                finishedAt,
                run.executionContractJson()
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

    private GitWorkspace withWorkspaceFailure(GitWorkspace workspace) {
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                com.agentx.platform.domain.execution.model.GitWorkspaceStatus.FAILED,
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                workspace.mergeCommit(),
                workspace.cleanupStatus()
        );
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize json payload", exception);
        }
    }

    private JsonPayload mergeJson(JsonPayload payload, Map<String, Object> additions) {
        Map<String, Object> merged = new HashMap<>();
        if (payload != null) {
            try {
                merged.putAll(objectMapper.readValue(payload.json(), MAP_TYPE));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("failed to parse json payload", exception);
            }
        }
        merged.putAll(additions);
        return jsonPayload(merged);
    }

    private Map<String, Object> mapWithNullableEntry(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }

    private String payloadValue(JsonPayload payload, String key) {
        if (payload == null) {
            return null;
        }
        try {
            Map<String, Object> data = objectMapper.readValue(payload.json(), MAP_TYPE);
            Object value = data.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse payload", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private LocalDateTime leaseUntil() {
        return now().plus(runtimeProperties.getLeaseTtl());
    }

    private String runId(String taskId, int attemptNumber) {
        return taskId + "-run-" + String.format("%03d", attemptNumber);
    }

    private String snapshotId(String taskId, int attemptNumber) {
        return taskId + "-snapshot-" + String.format("%03d", attemptNumber);
    }

    private String agentInstanceId(String taskId, int attemptNumber) {
        return "ainst-" + shortToken(taskId, String.valueOf(attemptNumber));
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
}

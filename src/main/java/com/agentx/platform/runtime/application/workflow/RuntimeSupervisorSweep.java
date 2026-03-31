package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
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
import com.agentx.platform.runtime.agentruntime.ContainerObservation;
import com.agentx.platform.runtime.agentruntime.ContainerState;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class RuntimeSupervisorSweep {

    private static final String ARCHITECT_AGENT_ID = "architect-agent";

    private final ExecutionStore executionStore;
    private final PlanningStore planningStore;
    private final IntakeStore intakeStore;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final AgentRuntime agentRuntime;
    private final RuntimeInfrastructureProperties runtimeProperties;
    private final ObjectMapper objectMapper;

    public RuntimeSupervisorSweep(
            ExecutionStore executionStore,
            PlanningStore planningStore,
            IntakeStore intakeStore,
            WorkspaceProvisioner workspaceProvisioner,
            AgentRuntime agentRuntime,
            RuntimeInfrastructureProperties runtimeProperties,
            ObjectMapper objectMapper
    ) {
        this.executionStore = executionStore;
        this.planningStore = planningStore;
        this.intakeStore = intakeStore;
        this.workspaceProvisioner = workspaceProvisioner;
        this.agentRuntime = agentRuntime;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<SupervisorRecoveryDecision> sweepOnce() {
        List<SupervisorRecoveryDecision> decisions = new ArrayList<>();
        for (TaskRun run : executionStore.listActiveTaskRuns()) {
            decisions.add(reconcileRun(run));
        }
        for (GitWorkspace workspace : executionStore.listWorkspacesPendingCleanup()) {
            if (workspace.cleanupStatus() == CleanupStatus.DONE) {
                continue;
            }
            if (workspace.status() != GitWorkspaceStatus.FAILED && workspace.cleanupStatus() != CleanupStatus.FAILED) {
                continue;
            }
            executionStore.saveWorkspace(workspaceProvisioner.cleanup(workspace));
        }
        List<TaskRun> activeRuns = executionStore.listActiveTaskRuns();
        for (AgentPoolInstance instance : executionStore.listActiveAgentInstances()) {
            boolean ownedByActiveRun = activeRuns.stream()
                    .anyMatch(run -> run.agentInstanceId().equals(instance.agentInstanceId()));
            if (!ownedByActiveRun && instance.status() != AgentPoolStatus.DISABLED) {
                executionStore.saveAgentInstance(disabledInstance(instance, appendRuntimeMetadata(instance.runtimeMetadataJson(), "state", "detached")));
            }
        }
        return decisions;
    }

    private SupervisorRecoveryDecision reconcileRun(TaskRun run) {
        AgentPoolInstance agentInstance = executionStore.findAgentInstance(run.agentInstanceId())
                .orElseThrow(() -> new IllegalStateException("missing agent instance " + run.agentInstanceId()));
        ContainerObservation observation = agentRuntime.observe(agentInstance);
        if (observation.state() == ContainerState.RUNNING) {
            executionStore.saveTaskRun(refreshHeartbeat(run));
            executionStore.saveAgentInstance(refreshAgentHeartbeat(agentInstance));
            return new SupervisorRecoveryDecision(run.runId(), "HEARTBEAT_REFRESHED", "container is still running");
        }

        WorkTask task = planningStore.findTask(run.taskId())
                .orElseThrow(() -> new IllegalStateException("missing task " + run.taskId()));
        String workflowRunId = planningStore.findWorkflowRunIdByTask(task.taskId())
                .orElseThrow(() -> new IllegalStateException("missing workflow run for task " + task.taskId()));
        Optional<GitWorkspace> workspace = executionStore.findWorkspaceByRun(run.runId());

        if (observation.state() == ContainerState.EXITED && observation.exitCode() != null && observation.exitCode() == 0 && !observation.timedOut()) {
            Map<String, Object> successPayload = new LinkedHashMap<>();
            successPayload.put("workflowRunId", workflowRunId);
            successPayload.put("taskId", task.taskId());
            successPayload.put("agentInstanceId", run.agentInstanceId());
            if (observation.logOutput() != null) {
                successPayload.put("logs", observation.logOutput());
            }
            executionStore.saveTaskRun(finalizeRun(run, TaskRunStatus.SUCCEEDED, observation.finishedAt()));
            executionStore.appendTaskRunEvent(new TaskRunEvent(
                    eventId("task-run"),
                    run.runId(),
                    "RUN_FINISHED",
                    "runtime supervisor observed successful container exit",
                    jsonPayload(successPayload)
            ));
            workspace.ifPresent(value -> executionStore.saveWorkspace(workspaceProvisioner.refreshHeadCommit(value)));
            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.DELIVERED));
            executionStore.saveAgentInstance(disabledInstance(agentInstance, appendRuntimeMetadata(agentInstance.runtimeMetadataJson(), "state", "succeeded")));
            return new SupervisorRecoveryDecision(run.runId(), "RUN_SUCCEEDED", "container exited successfully");
        }

        agentRuntime.terminate(agentInstance);
        executionStore.saveTaskRun(finalizeRun(
                run,
                TaskRunStatus.FAILED,
                observation.finishedAt() == null ? now() : observation.finishedAt()
        ));
        Map<String, Object> failurePayload = new LinkedHashMap<>();
        failurePayload.put("workflowRunId", workflowRunId);
        failurePayload.put("taskId", task.taskId());
        failurePayload.put("agentInstanceId", run.agentInstanceId());
        failurePayload.put("timedOut", observation.timedOut());
        if (observation.exitCode() != null) {
            failurePayload.put("exitCode", observation.exitCode());
        }
        if (observation.logOutput() != null) {
            failurePayload.put("logs", observation.logOutput());
        }
        executionStore.appendTaskRunEvent(new TaskRunEvent(
                eventId("task-run"),
                run.runId(),
                "RUN_FAILED",
                "runtime supervisor observed failed container exit",
                jsonPayload(failurePayload)
        ));
        executionStore.saveAgentInstance(disabledInstance(agentInstance, appendRuntimeMetadata(agentInstance.runtimeMetadataJson(), "state", "failed")));
        workspace.ifPresent(value -> executionStore.saveWorkspace(workspaceProvisioner.cleanup(withWorkspaceFailure(value))));

        if (executionStore.listTaskRuns(task.taskId()).size() < runtimeProperties.getMaxRunAttempts()) {
            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.READY));
            return new SupervisorRecoveryDecision(run.runId(), "RUN_REQUEUED", "run failed below retry limit");
        }

        planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
        createRuntimeAlertTicket(workflowRunId, task, run.runId(), "run failed after max retry budget");
        return new SupervisorRecoveryDecision(run.runId(), "RUN_BLOCKED", "run failed after max retry budget");
    }

    private TaskRun refreshHeartbeat(TaskRun run) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                run.status(),
                run.runKind(),
                run.contextSnapshotId(),
                leaseUntil(),
                now(),
                run.startedAt(),
                run.finishedAt(),
                run.executionContractJson()
        );
    }

    private AgentPoolInstance refreshAgentHeartbeat(AgentPoolInstance agentInstance) {
        return new AgentPoolInstance(
                agentInstance.agentInstanceId(),
                agentInstance.agentId(),
                agentInstance.runtimeType(),
                agentInstance.status(),
                agentInstance.launchMode(),
                agentInstance.currentWorkflowRunId(),
                leaseUntil(),
                now(),
                agentInstance.endpointRef(),
                agentInstance.runtimeMetadataJson()
        );
    }

    private TaskRun finalizeRun(TaskRun run, TaskRunStatus status, LocalDateTime finishedAt) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                status,
                run.runKind(),
                run.contextSnapshotId(),
                now(),
                now(),
                run.startedAt(),
                finishedAt,
                run.executionContractJson()
        );
    }

    private AgentPoolInstance disabledInstance(AgentPoolInstance agentInstance, JsonPayload metadata) {
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
                metadata
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
                "ticket-runtime-" + shortToken(task.taskId(), runId),
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

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize json payload", exception);
        }
    }

    private JsonPayload appendRuntimeMetadata(JsonPayload payload, String key, String value) {
        try {
            Map<String, Object> data = payload == null
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(payload.json(), Map.class);
            data.put(key, value);
            return jsonPayload(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to merge runtime metadata", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private LocalDateTime leaseUntil() {
        return now().plus(runtimeProperties.getLeaseTtl());
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
}

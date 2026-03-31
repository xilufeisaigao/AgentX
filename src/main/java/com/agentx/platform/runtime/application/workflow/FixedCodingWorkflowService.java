package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowBindingMode;
import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowTemplate;
import com.agentx.platform.domain.flow.model.WorkflowTemplateNode;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class FixedCodingWorkflowService implements FixedCodingWorkflowUseCase {

    private static final String TEMPLATE_ID = "builtin-coding-flow";

    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final RequirementStageService requirementStageService;
    private final WorkflowDriverService workflowDriverService;
    private final WorkflowScenarioResolver workflowScenarioResolver;
    private final StackProfileRegistry stackProfileRegistry;
    private final RuntimeInfrastructureProperties runtimeProperties;
    private final ObjectMapper objectMapper;

    public FixedCodingWorkflowService(
            FlowStore flowStore,
            IntakeStore intakeStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            RequirementStageService requirementStageService,
            WorkflowDriverService workflowDriverService,
            WorkflowScenarioResolver workflowScenarioResolver,
            StackProfileRegistry stackProfileRegistry,
            RuntimeInfrastructureProperties runtimeProperties,
            ObjectMapper objectMapper
    ) {
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.requirementStageService = requirementStageService;
        this.workflowDriverService = workflowDriverService;
        this.workflowScenarioResolver = workflowScenarioResolver;
        this.stackProfileRegistry = stackProfileRegistry;
        this.runtimeProperties = runtimeProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String start(StartCodingWorkflowCommand command) {
        WorkflowTemplate template = flowStore.findTemplate(TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException("missing workflow template " + TEMPLATE_ID));
        ActiveStackProfileSnapshot activeProfile = stackProfileRegistry.resolveRequired(command.profileId());
        String workflowRunId = "workflow-" + randomId();
        WorkflowRun workflowRun = new WorkflowRun(
                workflowRunId,
                TEMPLATE_ID,
                command.title(),
                WorkflowRunStatus.ACTIVE,
                EntryMode.MANUAL,
                command.autoAgentMode(),
                command.createdBy()
        );
        flowStore.saveRun(workflowRun);

        for (WorkflowTemplateNode node : template.nodes()) {
            if (node.defaultAgentId() == null) {
                continue;
            }
            String selectedAgentId = Optional.ofNullable(activeProfile.nodeAgentId(node.nodeId()))
                    .filter(agentId -> !agentId.isBlank())
                    .orElse(node.defaultAgentId());
            flowStore.saveNodeBinding(new WorkflowNodeBinding(
                    "binding-" + workflowRunId + "-" + node.nodeId(),
                    workflowRunId,
                    node.nodeId(),
                    WorkflowBindingMode.DEFAULT,
                    selectedAgentId,
                    false
            ));
        }

        flowStore.appendRunEvent(new WorkflowRunEvent(
                "workflow-event-" + randomId(),
                workflowRunId,
                "WORKFLOW_STARTED",
                command.createdBy(),
                "固定代码交付流程已启动。",
                startEventPayload(command, activeProfile)
        ));
        return workflowRunId;
    }

    @Override
    public WorkflowRuntimeSnapshot runUntilStable(String workflowRunId) {
        WorkflowRuntimeSnapshot snapshot = getRuntimeSnapshot(workflowRunId);
        long deadline = nextDeadline();
        String progressSignature = progressSignature(snapshot);
        while (System.nanoTime() < deadline) {
            if (isStableWithoutResume(snapshot.workflowRun()) || isStableAgentBlocked(snapshot)) {
                return snapshot;
            }
            workflowDriverService.driveWorkflowOnce(workflowRunId);
            WorkflowRuntimeSnapshot progressedSnapshot = getRuntimeSnapshot(workflowRunId);
            if (isStableAfterInvoke(progressedSnapshot.workflowRun()) || isStableAgentBlocked(progressedSnapshot)) {
                return progressedSnapshot;
            }
            String progressedSignature = progressSignature(progressedSnapshot);
            if (!progressedSignature.equals(progressSignature)) {
                progressSignature = progressedSignature;
                deadline = nextDeadline();
            }
            snapshot = progressedSnapshot;
            sleepQuietly();
        }
        snapshot = getRuntimeSnapshot(workflowRunId);
        throw new IllegalStateException(
                "workflow did not reach a stable state before timeout: "
                        + workflowRunId
                        + " status="
                        + snapshot.workflowRun().status()
                        + " tasks="
                        + snapshot.tasks().stream().map(task -> task.taskId() + ":" + task.status()).toList()
                        + " taskRuns="
                        + snapshot.taskRuns().stream().map(run -> run.runId() + ":" + run.status()).toList()
                        + " workspaces="
                        + snapshot.workspaces().stream().map(workspace -> workspace.workspaceId() + ":" + workspace.status()).toList()
        );
    }

    @Override
    @Transactional
    public WorkflowRuntimeSnapshot answerTicket(AnswerTicketCommand command) {
        Ticket currentTicket = intakeStore.findTicket(command.ticketId())
                .orElseThrow(() -> new IllegalArgumentException("ticket not found: " + command.ticketId()));
        JsonPayload mergedPayload = mergeJson(currentTicket.payloadJson(), Map.of(
                "answer", command.answer(),
                "answeredByActorType", command.answeredBy().type().name(),
                "answeredByActorId", command.answeredBy().actorId()
        ));
        Ticket answeredTicket = new Ticket(
                currentTicket.ticketId(),
                currentTicket.workflowRunId(),
                currentTicket.type(),
                currentTicket.blockingScope(),
                TicketStatus.ANSWERED,
                currentTicket.title(),
                currentTicket.createdBy(),
                currentTicket.assignee(),
                currentTicket.originNodeId(),
                currentTicket.requirementDocId(),
                currentTicket.requirementDocVersion(),
                currentTicket.taskId(),
                mergedPayload
        );
        intakeStore.saveTicket(answeredTicket);
        intakeStore.appendTicketEvent(new TicketEvent(
                "ticket-event-" + randomId(),
                answeredTicket.ticketId(),
                "USER_ANSWERED",
                command.answeredBy(),
                "人类已回复当前 ticket。",
                mergedPayload
        ));

        WorkflowRun workflowRun = workflow(answeredTicket.workflowRunId());
        if (workflowRun.status() == WorkflowRunStatus.WAITING_ON_HUMAN) {
            flowStore.saveRun(new WorkflowRun(
                    workflowRun.workflowRunId(),
                    workflowRun.workflowTemplateId(),
                    workflowRun.title(),
                    WorkflowRunStatus.ACTIVE,
                    workflowRun.entryMode(),
                    workflowRun.autoAgentMode(),
                    workflowRun.createdBy()
            ));
        }
        return getRuntimeSnapshot(answeredTicket.workflowRunId());
    }

    @Override
    @Transactional
    public WorkflowRuntimeSnapshot confirmRequirementDoc(ConfirmRequirementDocCommand command) {
        return getRuntimeSnapshot(requirementStageService.confirmRequirementDoc(command));
    }

    @Override
    @Transactional
    public WorkflowRuntimeSnapshot editRequirementDoc(EditRequirementDocCommand command) {
        return getRuntimeSnapshot(requirementStageService.editRequirementDoc(command));
    }

    @Override
    public WorkflowRuntimeSnapshot getRuntimeSnapshot(String workflowRunId) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        Optional<RequirementDoc> requirementDoc = intakeStore.findRequirementByWorkflow(workflowRunId);
        List<RequirementVersion> versions = requirementDoc
                .map(doc -> intakeStore.listRequirementVersions(doc.docId()))
                .orElse(List.of());
        List<Ticket> tickets = intakeStore.listTicketsForWorkflow(workflowRunId);
        List<WorkTask> tasks = planningStore.listTasksByWorkflow(workflowRunId);
        List<TaskContextSnapshot> snapshots = new ArrayList<>();
        List<TaskRun> taskRuns = new ArrayList<>();
        List<GitWorkspace> workspaces = new ArrayList<>();
        for (WorkTask task : tasks) {
            snapshots.addAll(executionStore.listSnapshots(task.taskId(), RunKind.IMPL));
            taskRuns.addAll(executionStore.listTaskRuns(task.taskId()));
            workspaces.addAll(executionStore.listWorkspaces(task.taskId()));
        }
        return new WorkflowRuntimeSnapshot(
                workflowRun,
                workflowScenarioResolver.resolveProfileRef(workflowRunId),
                requirementDoc,
                versions,
                tickets,
                tasks,
                snapshots,
                taskRuns,
                workspaces,
                flowStore.listNodeRuns(workflowRunId)
        );
    }

    private WorkflowRun workflow(String workflowRunId) {
        return flowStore.findRun(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + workflowRunId));
    }

    private boolean isStableWithoutResume(WorkflowRun workflowRun) {
        if (workflowRun.status() == WorkflowRunStatus.COMPLETED
                || workflowRun.status() == WorkflowRunStatus.FAILED
                || workflowRun.status() == WorkflowRunStatus.CANCELED) {
            return true;
        }
        if (isAsyncMacroStable(workflowRun.status())) {
            return true;
        }
        if (workflowRun.status() != WorkflowRunStatus.WAITING_ON_HUMAN) {
            return false;
        }
        return intakeStore.listOpenTickets(workflowRun.workflowRunId()).stream()
                .noneMatch(ticket -> ticket.assignee().type() == ActorType.HUMAN && ticket.status() == TicketStatus.ANSWERED);
    }

    private boolean isStableAfterInvoke(WorkflowRun workflowRun) {
        return workflowRun.status() == WorkflowRunStatus.COMPLETED
                || workflowRun.status() == WorkflowRunStatus.FAILED
                || workflowRun.status() == WorkflowRunStatus.CANCELED
                || workflowRun.status() == WorkflowRunStatus.WAITING_ON_HUMAN
                || isAsyncMacroStable(workflowRun.status());
    }

    private boolean isStableAgentBlocked(WorkflowRuntimeSnapshot snapshot) {
        boolean hasOpenNonHumanBlocker = snapshot.tickets().stream()
                .anyMatch(ticket -> ticket.status() == TicketStatus.OPEN
                        && ticket.assignee().type() != ActorType.HUMAN
                        && ticket.blockingScope() != TicketBlockingScope.INFORMATIONAL);
        boolean hasActiveRun = snapshot.taskRuns().stream()
                .anyMatch(run -> run.status() == TaskRunStatus.QUEUED || run.status() == TaskRunStatus.RUNNING);
        return hasOpenNonHumanBlocker && !hasActiveRun;
    }

    private long nextDeadline() {
        return System.nanoTime() + runtimeProperties.getBlockingTimeout().toNanos();
    }

    private String progressSignature(WorkflowRuntimeSnapshot snapshot) {
        StringBuilder signature = new StringBuilder();
        signature.append(snapshot.workflowRun().status()).append('|');
        snapshot.tasks().forEach(task -> signature.append(task.taskId()).append(':').append(task.status()).append('|'));
        snapshot.taskRuns().forEach(run -> signature.append(run.runId())
                .append(':').append(run.status())
                .append(':').append(run.lastHeartbeatAt())
                .append(':').append(run.finishedAt())
                .append('|'));
        snapshot.workspaces().forEach(workspace -> signature.append(workspace.workspaceId())
                .append(':').append(workspace.status())
                .append(':').append(workspace.headCommit())
                .append(':').append(workspace.mergeCommit())
                .append(':').append(workspace.cleanupStatus())
                .append('|'));
        snapshot.tickets().forEach(ticket -> signature.append(ticket.ticketId())
                .append(':').append(ticket.status())
                .append(':').append(ticket.assignee().type())
                .append('|'));
        signature.append("nodeRuns=").append(snapshot.nodeRuns().size());
        if (!snapshot.nodeRuns().isEmpty()) {
            var lastNodeRun = snapshot.nodeRuns().getLast();
            signature.append(':').append(lastNodeRun.nodeRunId())
                    .append(':').append(lastNodeRun.status())
                    .append(':').append(lastNodeRun.finishedAt());
        }
        return signature.toString();
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(runtimeProperties.getBlockingPollInterval().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workflow wait interrupted", exception);
        }
    }

    private JsonPayload startEventPayload(StartCodingWorkflowCommand command, ActiveStackProfileSnapshot activeProfile) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(Map.of(
                    "requireHumanClarification", command.scenario().requireHumanClarification(),
                    "architectCanAutoResolveClarification", command.scenario().architectCanAutoResolveClarification(),
                    "verifyNeedsRework", command.scenario().verifyNeedsRework(),
                    "profileId", activeProfile.profileId(),
                    "profileDisplayName", activeProfile.displayName(),
                    "profileVersion", activeProfile.version(),
                    "profileDigest", activeProfile.digest(),
                    "requirementSeedTitle", command.requirementTitle(),
                    "requirementSeedContent", command.requirementContent()
            )));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize workflow scenario", exception);
        }
    }

    private JsonPayload mergeJson(JsonPayload payload, Map<String, Object> additions) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> merged = payload == null
                    ? new java.util.HashMap<>()
                    : objectMapper.readValue(payload.json(), Map.class);
            merged.putAll(additions);
            return new JsonPayload(objectMapper.writeValueAsString(merged));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to merge ticket payload", exception);
        }
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private boolean isAsyncMacroStable(WorkflowRunStatus status) {
        if (!runtimeProperties.isDriverEnabled()) {
            return false;
        }
        return status == WorkflowRunStatus.EXECUTING_TASKS || status == WorkflowRunStatus.VERIFYING;
    }
}

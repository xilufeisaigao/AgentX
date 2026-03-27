package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
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
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.local.LocalRequirementAgent;
import com.agentx.platform.runtime.orchestration.langgraph.FixedCodingGraphFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FixedCodingWorkflowService implements FixedCodingWorkflowUseCase {

    private static final String TEMPLATE_ID = "builtin-coding-flow";

    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final LocalRequirementAgent requirementAgent;
    private final FixedCodingGraphFactory graphFactory;
    private final ObjectMapper objectMapper;

    public FixedCodingWorkflowService(
            FlowStore flowStore,
            IntakeStore intakeStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            LocalRequirementAgent requirementAgent,
            FixedCodingGraphFactory graphFactory,
            ObjectMapper objectMapper
    ) {
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.requirementAgent = requirementAgent;
        this.graphFactory = graphFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public String start(StartCodingWorkflowCommand command) {
        WorkflowTemplate template = flowStore.findTemplate(TEMPLATE_ID)
                .orElseThrow(() -> new IllegalStateException("missing workflow template " + TEMPLATE_ID));
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
            flowStore.saveNodeBinding(new WorkflowNodeBinding(
                    "binding-" + workflowRunId + "-" + node.nodeId(),
                    workflowRunId,
                    node.nodeId(),
                    WorkflowBindingMode.DEFAULT,
                    node.defaultAgentId(),
                    false
            ));
        }

        String requirementDocId = "requirement-" + workflowRunId;
        LocalRequirementAgent.RequirementDraft draft =
                requirementAgent.createConfirmedRequirement(workflowRunId, requirementDocId, command);
        intakeStore.saveRequirement(draft.requirementDoc());
        intakeStore.appendRequirementVersion(draft.requirementVersion());
        flowStore.appendRunEvent(new WorkflowRunEvent(
                "workflow-event-" + randomId(),
                workflowRunId,
                "WORKFLOW_STARTED",
                command.createdBy(),
                "固定代码交付流程已启动。",
                scenarioJson(command.scenario(), requirementDocId)
        ));
        return workflowRunId;
    }

    @Override
    public WorkflowRuntimeSnapshot runUntilStable(String workflowRunId) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        if (isStableWithoutResume(workflowRun)) {
            return getRuntimeSnapshot(workflowRunId);
        }

        graphFactory.compiledGraph().invoke(
                Map.of("workflowRunId", workflowRunId),
                RunnableConfig.builder().threadId(workflowRunId).build()
        );

        WorkflowRun completedRun = workflow(workflowRunId);
        if (!isStableAfterInvoke(completedRun)) {
            throw new IllegalStateException("workflow did not reach a stable state: " + completedRun.status());
        }
        return getRuntimeSnapshot(workflowRunId);
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
    public WorkflowRuntimeSnapshot getRuntimeSnapshot(String workflowRunId) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        RequirementDoc requirementDoc = intakeStore.findRequirementByWorkflow(workflowRunId)
                .orElseThrow(() -> new IllegalStateException("missing requirement doc for workflow " + workflowRunId));
        List<RequirementVersion> versions = intakeStore.listRequirementVersions(requirementDoc.docId());
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
                || workflowRun.status() == WorkflowRunStatus.WAITING_ON_HUMAN;
    }

    private JsonPayload scenarioJson(WorkflowScenario scenario, String requirementDocId) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(Map.of(
                    "requireHumanClarification", scenario.requireHumanClarification(),
                    "architectCanAutoResolveClarification", scenario.architectCanAutoResolveClarification(),
                    "verifyNeedsRework", scenario.verifyNeedsRework(),
                    "requirementDocId", requirementDocId
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
}

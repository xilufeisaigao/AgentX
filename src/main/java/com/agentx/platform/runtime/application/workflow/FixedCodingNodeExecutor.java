package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskContextSnapshotStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.policy.ExecutionPolicy;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketEvent;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.local.LocalArchitectAgent;
import com.agentx.platform.runtime.agentruntime.local.LocalCodingAgent;
import com.agentx.platform.runtime.agentruntime.local.LocalVerifyAgent;
import com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState;
import com.agentx.platform.runtime.workspace.git.SyntheticWorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class FixedCodingNodeExecutor {

    private static final String ARCHITECT_AGENT_ID = "architect-agent";
    private static final String CODING_AGENT_ID = "coding-agent-java";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final LocalArchitectAgent architectAgent;
    private final LocalCodingAgent codingAgent;
    private final LocalVerifyAgent verifyAgent;
    private final SyntheticWorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public FixedCodingNodeExecutor(
            FlowStore flowStore,
            IntakeStore intakeStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            LocalArchitectAgent architectAgent,
            LocalCodingAgent codingAgent,
            LocalVerifyAgent verifyAgent,
            SyntheticWorkspaceService workspaceService,
            ObjectMapper objectMapper
    ) {
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.architectAgent = architectAgent;
        this.codingAgent = codingAgent;
        this.verifyAgent = verifyAgent;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> requirementNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "requirement", () -> {
            RequirementDoc requirementDoc = requirement(workflowRunId);
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.ACTIVE);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "requirementDocId", requirementDoc.docId(),
                            "status", requirementDoc.status().name()
                    )),
                    "需求文档已确认，可以进入架构阶段。"
            );
        });
    }

    @Transactional
    public Map<String, Object> architectNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "architect", () -> {
            WorkflowScenario scenario = scenario(workflowRunId);
            resolveAnsweredHumanTickets(workflowRunId);
            triageArchitectTickets(workflowRunId, scenario);
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.EXECUTING_TASKS);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "openTickets", intakeStore.listOpenTickets(workflowRunId).size(),
                            "tasks", planningStore.listTasksByWorkflow(workflowRunId).size()
                    )),
                    "架构节点已完成澄清分流和任务阶段判断。"
            );
        });
    }

    @Transactional
    public Map<String, Object> ticketGateNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "ticket-gate", () -> {
            long pendingHumanTickets = pendingHumanTickets(workflowRunId).size();
            if (pendingHumanTickets > 0) {
                saveWorkflowStatus(workflowRunId, WorkflowRunStatus.WAITING_ON_HUMAN);
                return NodeOutcome.waitingOnHuman(
                        jsonPayload(Map.of("pendingHumanTickets", pendingHumanTickets)),
                        "存在等待人类回答的 ticket，流程暂停。"
                );
            }
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.ACTIVE);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of("pendingHumanTickets", 0)),
                    "工单中心确认当前无需继续等待人类输入。"
            );
        });
    }

    @Transactional
    public Map<String, Object> taskGraphNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "task-graph", () -> {
            List<WorkTask> existingTasks = planningStore.listTasksByWorkflow(workflowRunId);
            if (existingTasks.isEmpty()) {
                LocalArchitectAgent.PlannedTaskGraph plan = architectAgent.plan(workflowRunId);
                WorkModule module = new WorkModule(
                        plan.moduleId(),
                        workflowRunId,
                        plan.moduleName(),
                        plan.moduleDescription()
                );
                planningStore.saveModule(module);

                WorkTask plannedTask = new WorkTask(
                        plan.taskId(),
                        plan.moduleId(),
                        plan.taskTitle(),
                        plan.taskObjective(),
                        "java-backend-task",
                        WorkTaskStatus.PLANNED,
                        plan.writeScopes(),
                        null,
                        new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID)
                );
                planningStore.saveTask(plannedTask);
                planningStore.saveCapabilityRequirement(new TaskCapabilityRequirement(
                        plan.taskId(),
                        plan.capabilityPackId(),
                        true,
                        "PRIMARY"
                ));

                // This fixed v1 graph has no upstream dependency, so the task can open immediately.
                planningStore.saveTask(withTaskStatus(plannedTask, WorkTaskStatus.READY));
            }
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of("taskCount", planningStore.listTasksByWorkflow(workflowRunId).size())),
                    "固定任务图已经存在。"
            );
        });
    }

    @Transactional
    public Map<String, Object> workerManagerNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "worker-manager", () -> {
            int dispatched = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.READY) {
                    continue;
                }
                List<TaskRun> existingRuns = executionStore.listTaskRuns(task.taskId());
                if (existingRuns.stream().anyMatch(this::isActiveRun)) {
                    continue;
                }
                int attemptNumber = existingRuns.size() + 1;
                AgentPoolInstance agent = obtainOrProvisionAgent(workflowRunId, task);
                TaskContextSnapshot snapshot = buildSnapshot(workflowRunId, task, attemptNumber);
                ExecutionPolicy.assertAgentReady(agent);
                ExecutionPolicy.assertSnapshotReady(snapshot);
                executionStore.saveTaskContextSnapshot(snapshot);

                TaskRun taskRun = new TaskRun(
                        runId(task.taskId(), attemptNumber),
                        task.taskId(),
                        agent.agentInstanceId(),
                        TaskRunStatus.QUEUED,
                        RunKind.IMPL,
                        snapshot.snapshotId(),
                        leaseUntil(),
                        now(),
                        now(),
                        null,
                        jsonPayload(Map.of(
                                "attempt", attemptNumber,
                                "taskId", task.taskId(),
                                "workflowRunId", workflowRunId
                        ))
                );
                executionStore.saveTaskRun(taskRun);
                executionStore.appendTaskRunEvent(new TaskRunEvent(
                        eventId("task-run"),
                        taskRun.runId(),
                        "RUN_CREATED",
                        "任务运行已创建，等待编码节点执行。",
                        jsonPayload(Map.of("attempt", attemptNumber))
                ));

                GitWorkspace workspace = workspaceService.allocate(workflowRunId, task, taskRun);
                executionStore.saveWorkspace(workspace);
                planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.IN_PROGRESS));
                dispatched++;
            }
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of("dispatchedTasks", dispatched)),
                    "工作代理管理器已完成任务派发。"
            );
        });
    }

    @Transactional
    public Map<String, Object> codingNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "coding", () -> {
            WorkflowScenario scenario = scenario(workflowRunId);
            int clarificationCount = 0;
            int deliveredCount = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.IN_PROGRESS) {
                    continue;
                }
                TaskRun run = latestRun(task.taskId()).orElseThrow(() ->
                        new IllegalStateException("missing task run for in-progress task " + task.taskId()));
                if (run.status() == TaskRunStatus.SUCCEEDED || run.status() == TaskRunStatus.CANCELED) {
                    continue;
                }
                GitWorkspace workspace = executionStore.findWorkspaceByRun(run.runId()).orElseThrow(() ->
                        new IllegalStateException("missing workspace for run " + run.runId()));

                TaskRun runningRun = run.status() == TaskRunStatus.QUEUED ? withRunStatus(run, TaskRunStatus.RUNNING, null) : run;
                if (run.status() == TaskRunStatus.QUEUED) {
                    executionStore.saveTaskRun(runningRun);
                    executionStore.appendTaskRunEvent(new TaskRunEvent(
                            eventId("task-run"),
                            runningRun.runId(),
                            "RUN_STARTED",
                            "编码节点开始执行任务运行。",
                            null
                    ));
                }

                int attemptNumber = executionStore.listTaskRuns(task.taskId()).size();
                LocalCodingAgent.CodingTaskOutcome outcome = codingAgent.execute(task, attemptNumber, scenario, workspace);
                if (outcome.type() == LocalCodingAgent.CodingOutcomeType.NEED_CLARIFICATION) {
                    executionStore.saveTaskRun(withRunStatus(runningRun, TaskRunStatus.CANCELED, now()));
                    executionStore.appendTaskRunEvent(new TaskRunEvent(
                            eventId("task-run"),
                            runningRun.runId(),
                            "NEED_CLARIFICATION",
                            outcome.body(),
                            mergeJson(outcome.payload(), Map.of(
                                    "taskId", task.taskId(),
                                    "runId", runningRun.runId(),
                                    "requiresHuman", outcome.requiresHuman()
                            ))
                    ));
                    planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
                    createClarificationTicket(workflowRunId, task, runningRun, outcome);
                    clarificationCount++;
                    continue;
                }

                executionStore.saveTaskRun(withRunStatus(runningRun, TaskRunStatus.SUCCEEDED, now()));
                GitWorkspace deliveredWorkspace = workspaceService.withHeadCommit(workspace);
                executionStore.saveWorkspace(deliveredWorkspace);
                executionStore.appendTaskRunEvent(new TaskRunEvent(
                        eventId("task-run"),
                        runningRun.runId(),
                        "RUN_FINISHED",
                        outcome.body(),
                        mergeJson(outcome.payload(), Map.of(
                                "resultStatus", "SUCCEEDED",
                                "headCommit", deliveredWorkspace.headCommit()
                        ))
                ));
                planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.DELIVERED));
                deliveredCount++;
            }
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "clarificationCount", clarificationCount,
                            "deliveredCount", deliveredCount
                    )),
                    "编码节点已处理当前可执行的任务。"
            );
        });
    }

    @Transactional
    public Map<String, Object> mergeGateNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "merge-gate", () -> {
            int mergedCount = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.DELIVERED) {
                    continue;
                }
                TaskRun successfulRun = latestSuccessfulRun(task.taskId()).orElseThrow(() ->
                        new IllegalStateException("missing successful run for delivered task " + task.taskId()));
                GitWorkspace workspace = executionStore.findWorkspaceByRun(successfulRun.runId()).orElseThrow(() ->
                        new IllegalStateException("missing workspace for delivered run " + successfulRun.runId()));
                if (workspace.status() == GitWorkspaceStatus.MERGED || workspace.status() == GitWorkspaceStatus.CLEANED) {
                    continue;
                }
                executionStore.saveWorkspace(workspaceService.markMerged(workspace));
                mergedCount++;
            }
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.VERIFYING);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of("mergedCount", mergedCount)),
                    "合并闸门已为交付候选生成逻辑 merge candidate。"
            );
        });
    }

    @Transactional
    public Map<String, Object> verifyNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "verify", () -> {
            WorkflowScenario scenario = scenario(workflowRunId);
            int passedCount = 0;
            int reworkCount = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.DELIVERED) {
                    continue;
                }
                GitWorkspace workspace = latestWorkspace(task.taskId()).orElseThrow(() ->
                        new IllegalStateException("missing workspace for delivered task " + task.taskId()));
                LocalVerifyAgent.VerifyOutcome verifyOutcome = verifyAgent.verify(task, workspace, scenario, 1);
                if (verifyOutcome.passed()) {
                    planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.DONE));
                    executionStore.saveWorkspace(workspaceService.markCleaned(workspace));
                    passedCount++;
                    continue;
                }
                planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.READY));
                reworkCount++;
            }

            WorkflowRunStatus finalStatus = planningStore.listTasksByWorkflow(workflowRunId).stream()
                    .allMatch(task -> task.status() == WorkTaskStatus.DONE)
                    ? WorkflowRunStatus.COMPLETED
                    : WorkflowRunStatus.EXECUTING_TASKS;
            saveWorkflowStatus(workflowRunId, finalStatus);

            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "passedCount", passedCount,
                            "reworkCount", reworkCount
                    )),
                    "验证节点已完成当前交付候选检查。"
            );
        });
    }

    public String routeAfterArchitect(PlatformWorkflowState state) {
        return pendingHumanTickets(state.workflowRunId()).isEmpty() ? "task-graph" : "ticket-gate";
    }

    public String routeAfterTicketGate(PlatformWorkflowState state) {
        return pendingHumanTickets(state.workflowRunId()).isEmpty() ? "architect" : "end";
    }

    public String routeAfterCoding(PlatformWorkflowState state) {
        return planningStore.listTasksByWorkflow(state.workflowRunId()).stream()
                .anyMatch(task -> task.status() == WorkTaskStatus.BLOCKED)
                ? "architect"
                : "merge-gate";
    }

    public String routeAfterVerify(PlatformWorkflowState state) {
        return planningStore.listTasksByWorkflow(state.workflowRunId()).stream()
                .allMatch(task -> task.status() == WorkTaskStatus.DONE)
                ? "end"
                : "architect";
    }

    private Map<String, Object> executeNode(String workflowRunId, String nodeId, Supplier<NodeOutcome> action) {
        LocalDateTime startedAt = now();
        JsonPayload inputPayload = jsonPayload(Map.of("workflowRunId", workflowRunId, "nodeId", nodeId));
        WorkflowNodeRun startedNodeRun = new WorkflowNodeRun(
                nodeRunId(nodeId),
                workflowRunId,
                nodeId,
                selectedAgentId(workflowRunId, nodeId),
                null,
                WorkflowNodeRunStatus.RUNNING,
                inputPayload,
                null,
                startedAt,
                null
        );
        flowStore.saveNodeRun(startedNodeRun);
        flowStore.appendNodeRunEvent(new WorkflowNodeRunEvent(
                eventId("node-run"),
                startedNodeRun.nodeRunId(),
                "NODE_STARTED",
                "节点开始执行。",
                inputPayload
        ));

        try {
            NodeOutcome outcome = action.get();
            WorkflowNodeRun completedNodeRun = new WorkflowNodeRun(
                    startedNodeRun.nodeRunId(),
                    startedNodeRun.workflowRunId(),
                    startedNodeRun.nodeId(),
                    startedNodeRun.selectedAgentId(),
                    startedNodeRun.agentInstanceId(),
                    outcome.status(),
                    startedNodeRun.inputPayloadJson(),
                    outcome.outputPayloadJson(),
                    startedNodeRun.startedAt(),
                    now()
            );
            flowStore.saveNodeRun(completedNodeRun);
            flowStore.appendNodeRunEvent(new WorkflowNodeRunEvent(
                    eventId("node-run"),
                    completedNodeRun.nodeRunId(),
                    "NODE_COMPLETED",
                    outcome.body(),
                    outcome.outputPayloadJson()
            ));
            return Map.of("workflowRunId", workflowRunId, "lastNode", nodeId);
        } catch (RuntimeException exception) {
            WorkflowNodeRun failedNodeRun = new WorkflowNodeRun(
                    startedNodeRun.nodeRunId(),
                    startedNodeRun.workflowRunId(),
                    startedNodeRun.nodeId(),
                    startedNodeRun.selectedAgentId(),
                    startedNodeRun.agentInstanceId(),
                    WorkflowNodeRunStatus.FAILED,
                    startedNodeRun.inputPayloadJson(),
                    jsonPayload(Map.of("error", exception.getMessage())),
                    startedNodeRun.startedAt(),
                    now()
            );
            flowStore.saveNodeRun(failedNodeRun);
            flowStore.appendNodeRunEvent(new WorkflowNodeRunEvent(
                    eventId("node-run"),
                    failedNodeRun.nodeRunId(),
                    "NODE_FAILED",
                    exception.getMessage(),
                    failedNodeRun.outputPayloadJson()
            ));
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.FAILED);
            throw exception;
        }
    }

    private void triageArchitectTickets(String workflowRunId, WorkflowScenario scenario) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        ActorRef humanOwner = workflowRun.createdBy().type() == ActorType.HUMAN
                ? workflowRun.createdBy()
                : new ActorRef(ActorType.HUMAN, "workflow-owner");

        for (Ticket ticket : intakeStore.listOpenTickets(workflowRunId)) {
            if (ticket.assignee().type() != ActorType.AGENT || !ARCHITECT_AGENT_ID.equals(ticket.assignee().actorId())) {
                continue;
            }
            LocalArchitectAgent.ClarificationDisposition disposition = architectAgent.reviewClarification(scenario);
            if (disposition.action() == LocalArchitectAgent.ClarificationAction.RESOLVE_DIRECTLY) {
                resolveTicketAndUnblockTask(ticket, disposition.body(), new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID));
                continue;
            }

            Ticket escalatedTicket = new Ticket(
                    ticket.ticketId(),
                    ticket.workflowRunId(),
                    ticket.type(),
                    ticket.blockingScope(),
                    TicketStatus.OPEN,
                    ticket.title(),
                    ticket.createdBy(),
                    humanOwner,
                    ticket.originNodeId(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVersion(),
                    mergeJson(ticket.payloadJson(), Map.of("escalatedToHuman", true))
            );
            intakeStore.saveTicket(escalatedTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    escalatedTicket.ticketId(),
                    "ESCALATED_TO_HUMAN",
                    new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
                    disposition.body(),
                    escalatedTicket.payloadJson()
            ));
        }
    }

    private void resolveAnsweredHumanTickets(String workflowRunId) {
        for (Ticket ticket : intakeStore.listOpenTickets(workflowRunId)) {
            if (ticket.assignee().type() != ActorType.HUMAN || ticket.status() != TicketStatus.ANSWERED) {
                continue;
            }
            LocalArchitectAgent.ClarificationDisposition disposition = architectAgent.applyHumanAnswer();
            resolveTicketAndUnblockTask(ticket, disposition.body(), new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID));
        }
    }

    private void resolveTicketAndUnblockTask(Ticket ticket, String body, ActorRef actor) {
        Ticket resolvedTicket = new Ticket(
                ticket.ticketId(),
                ticket.workflowRunId(),
                ticket.type(),
                ticket.blockingScope(),
                TicketStatus.RESOLVED,
                ticket.title(),
                ticket.createdBy(),
                actor,
                ticket.originNodeId(),
                ticket.requirementDocId(),
                ticket.requirementDocVersion(),
                ticket.payloadJson()
        );
        intakeStore.saveTicket(resolvedTicket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                resolvedTicket.ticketId(),
                "TICKET_RESOLVED",
                actor,
                body,
                resolvedTicket.payloadJson()
        ));

        String taskId = payloadString(ticket.payloadJson(), "taskId");
        if (taskId == null) {
            return;
        }
        planningStore.findTask(taskId)
                .filter(task -> task.status() == WorkTaskStatus.BLOCKED)
                .map(task -> withTaskStatus(task, WorkTaskStatus.READY))
                .ifPresent(planningStore::saveTask);
    }

    private void createClarificationTicket(
            String workflowRunId,
            WorkTask task,
            TaskRun run,
            LocalCodingAgent.CodingTaskOutcome outcome
    ) {
        String ticketId = "ticket-" + task.taskId() + "-clarification";
        JsonPayload payload = mergeJson(outcome.payload(), Map.of(
                "taskId", task.taskId(),
                "runId", run.runId(),
                "requiresHuman", outcome.requiresHuman()
        ));
        Ticket ticket = new Ticket(
                ticketId,
                workflowRunId,
                TicketType.CLARIFICATION,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "补齐编码任务所需事实",
                new ActorRef(ActorType.AGENT, CODING_AGENT_ID),
                new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
                "coding",
                null,
                null,
                payload
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "CLARIFICATION_REQUESTED",
                new ActorRef(ActorType.AGENT, CODING_AGENT_ID),
                outcome.body(),
                payload
        ));
    }

    private AgentPoolInstance obtainOrProvisionAgent(String workflowRunId, WorkTask task) {
        String capabilityPackId = planningStore.listCapabilityRequirements(task.taskId()).stream()
                .findFirst()
                .map(TaskCapabilityRequirement::capabilityPackId)
                .orElseThrow(() -> new IllegalStateException("missing capability requirement for task " + task.taskId()));

        List<AgentPoolInstance> readyAgents = executionStore.listReadyAgents(capabilityPackId);
        if (!readyAgents.isEmpty()) {
            AgentPoolInstance selected = readyAgents.get(0);
            AgentPoolInstance refreshed = new AgentPoolInstance(
                    selected.agentInstanceId(),
                    selected.agentId(),
                    selected.runtimeType(),
                    selected.status(),
                    selected.launchMode(),
                    workflowRunId,
                    leaseUntil(),
                    now(),
                    selected.endpointRef(),
                    selected.runtimeMetadataJson()
            );
            executionStore.saveAgentInstance(refreshed);
            return refreshed;
        }

        AgentPoolInstance provisioned = new AgentPoolInstance(
                agentInstanceId(workflowRunId, task.taskId()),
                CODING_AGENT_ID,
                "local-fake",
                AgentPoolStatus.READY,
                "LOCAL_FAKE",
                workflowRunId,
                leaseUntil(),
                now(),
                "local://coding/" + workflowRunId,
                jsonPayload(Map.of("provisionedBy", "worker-manager"))
        );
        executionStore.saveAgentInstance(provisioned);
        return provisioned;
    }

    private TaskContextSnapshot buildSnapshot(String workflowRunId, WorkTask task, int attemptNumber) {
        return new TaskContextSnapshot(
                snapshotId(task.taskId(), attemptNumber),
                task.taskId(),
                RunKind.IMPL,
                TaskContextSnapshotStatus.READY,
                attemptNumber == 1 ? "TASK_READY" : "TASK_RESUMED",
                "fp-" + workflowRunId + "-" + task.taskId() + "-" + attemptNumber,
                "runtime://context/" + workflowRunId + "/" + task.taskId() + "/" + attemptNumber,
                "runtime://skill/" + workflowRunId + "/" + task.taskId() + "/" + attemptNumber,
                now().plusDays(1)
        );
    }

    private List<Ticket> pendingHumanTickets(String workflowRunId) {
        return intakeStore.listOpenTickets(workflowRunId).stream()
                .filter(ticket -> ticket.assignee().type() == ActorType.HUMAN && ticket.status() != TicketStatus.ANSWERED)
                .toList();
    }

    private Optional<TaskRun> latestRun(String taskId) {
        List<TaskRun> runs = executionStore.listTaskRuns(taskId);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(runs.size() - 1));
    }

    private Optional<TaskRun> latestSuccessfulRun(String taskId) {
        TaskRun latest = null;
        for (TaskRun run : executionStore.listTaskRuns(taskId)) {
            if (run.status() == TaskRunStatus.SUCCEEDED) {
                latest = run;
            }
        }
        return Optional.ofNullable(latest);
    }

    private Optional<GitWorkspace> latestWorkspace(String taskId) {
        List<GitWorkspace> workspaces = executionStore.listWorkspaces(taskId);
        return workspaces.isEmpty() ? Optional.empty() : Optional.of(workspaces.get(workspaces.size() - 1));
    }

    private boolean isActiveRun(TaskRun run) {
        return run.status() == TaskRunStatus.QUEUED || run.status() == TaskRunStatus.RUNNING;
    }

    private WorkflowRun workflow(String workflowRunId) {
        return flowStore.findRun(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + workflowRunId));
    }

    private RequirementDoc requirement(String workflowRunId) {
        return intakeStore.findRequirementByWorkflow(workflowRunId)
                .orElseThrow(() -> new IllegalStateException("requirement doc not found for workflow " + workflowRunId));
    }

    private WorkflowScenario scenario(String workflowRunId) {
        WorkflowRunEvent latestStartEvent = null;
        for (WorkflowRunEvent event : flowStore.listRunEvents(workflowRunId)) {
            if ("WORKFLOW_STARTED".equals(event.eventType())) {
                latestStartEvent = event;
            }
        }
        return latestStartEvent == null ? WorkflowScenario.defaultScenario() : scenarioFromJson(latestStartEvent.dataJson());
    }

    private WorkflowScenario scenarioFromJson(JsonPayload payload) {
        if (payload == null) {
            return WorkflowScenario.defaultScenario();
        }
        try {
            Map<String, Object> data = objectMapper.readValue(payload.json(), MAP_TYPE);
            return new WorkflowScenario(
                    booleanValue(data, "requireHumanClarification"),
                    booleanValue(data, "architectCanAutoResolveClarification"),
                    booleanValue(data, "verifyNeedsRework")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse workflow scenario", exception);
        }
    }

    private String selectedAgentId(String workflowRunId, String nodeId) {
        for (var binding : flowStore.listNodeBindings(workflowRunId)) {
            if (binding.nodeId().equals(nodeId)) {
                return binding.selectedAgentId();
            }
        }
        return null;
    }

    private void saveWorkflowStatus(String workflowRunId, WorkflowRunStatus status) {
        WorkflowRun current = workflow(workflowRunId);
        if (current.status() == status) {
            return;
        }
        flowStore.saveRun(new WorkflowRun(
                current.workflowRunId(),
                current.workflowTemplateId(),
                current.title(),
                status,
                current.entryMode(),
                current.autoAgentMode(),
                current.createdBy()
        ));
        flowStore.appendRunEvent(new WorkflowRunEvent(
                eventId("workflow"),
                current.workflowRunId(),
                "WORKFLOW_STATUS_UPDATED",
                new ActorRef(ActorType.SYSTEM, "runtime"),
                "工作流状态已更新为 " + status.name(),
                jsonPayload(Map.of("status", status.name()))
        ));
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

    private TaskRun withRunStatus(TaskRun run, TaskRunStatus status, LocalDateTime finishedAt) {
        return new TaskRun(
                run.runId(),
                run.taskId(),
                run.agentInstanceId(),
                status,
                run.runKind(),
                run.contextSnapshotId(),
                status == TaskRunStatus.RUNNING ? leaseUntil() : now(),
                now(),
                run.startedAt(),
                finishedAt,
                run.executionContractJson()
        );
    }

    private JsonPayload mergeJson(JsonPayload basePayload, Map<String, Object> additions) {
        Map<String, Object> merged = new HashMap<>();
        if (basePayload != null) {
            try {
                merged.putAll(objectMapper.readValue(basePayload.json(), MAP_TYPE));
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("failed to merge json payload", exception);
            }
        }
        merged.putAll(additions);
        return jsonPayload(merged);
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to write json payload", exception);
        }
    }

    private String payloadString(JsonPayload payload, String key) {
        if (payload == null) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(payload.json(), MAP_TYPE);
            Object value = map.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse json payload", exception);
        }
    }

    private boolean booleanValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private LocalDateTime leaseUntil() {
        return now().plusMinutes(5);
    }

    private String runId(String taskId, int attemptNumber) {
        return taskId + "-run-" + String.format("%03d", attemptNumber);
    }

    private String snapshotId(String taskId, int attemptNumber) {
        return taskId + "-snapshot-" + String.format("%03d", attemptNumber);
    }

    private String agentInstanceId(String workflowRunId, String taskId) {
        // The DB truth caps agent_instance_id at varchar(64), so runtime-generated ids
        // must stay compact while still remaining deterministic for idempotent retries.
        return "ainst-" + shortToken(workflowRunId, taskId);
    }

    private String nodeRunId(String nodeId) {
        return nodeId + "-node-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String eventId(String prefix) {
        return prefix + "-event-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String shortToken(String... values) {
        return UUID.nameUUIDFromBytes(String.join("|", values).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 24);
    }

    private record NodeOutcome(
            WorkflowNodeRunStatus status,
            JsonPayload outputPayloadJson,
            String body
    ) {

        private static NodeOutcome succeeded(JsonPayload payload, String body) {
            return new NodeOutcome(WorkflowNodeRunStatus.SUCCEEDED, payload, body);
        }

        private static NodeOutcome waitingOnHuman(JsonPayload payload, String body) {
            return new NodeOutcome(WorkflowNodeRunStatus.WAITING_ON_HUMAN, payload, body);
        }
    }
}

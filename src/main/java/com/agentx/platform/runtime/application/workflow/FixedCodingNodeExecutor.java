package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowNodeRunStatus;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
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
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileRegistry;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecision;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecisionType;
import com.agentx.platform.runtime.agentkernel.architect.PlanningGraphSpec;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecision;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionAgent;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionType;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class FixedCodingNodeExecutor {

    private static final String ARCHITECT_AGENT_ID = "architect-agent";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CatalogStore catalogStore;
    private final FlowStore flowStore;
    private final IntakeStore intakeStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final RequirementStageService requirementStageService;
    private final ContextCompilationCenter contextCompilationCenter;
    private final ArchitectConversationAgent architectConversationAgent;
    private final VerifyDecisionAgent verifyDecisionAgent;
    private final PlanningGraphMaterializer planningGraphMaterializer;
    private final TaskDispatcher taskDispatcher;
    private final WorkspaceProvisioner workspaceProvisioner;
    private final AgentRuntime agentRuntime;
    private final TaskExecutionContractBuilder contractBuilder;
    private final ToolExecutor toolExecutor;
    private final WorkflowScenarioResolver scenarioResolver;
    private final StackProfileRegistry stackProfileRegistry;
    private final ObjectMapper objectMapper;

    public FixedCodingNodeExecutor(
            CatalogStore catalogStore,
            FlowStore flowStore,
            IntakeStore intakeStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            RequirementStageService requirementStageService,
            ContextCompilationCenter contextCompilationCenter,
            ArchitectConversationAgent architectConversationAgent,
            VerifyDecisionAgent verifyDecisionAgent,
            PlanningGraphMaterializer planningGraphMaterializer,
            TaskDispatcher taskDispatcher,
            WorkspaceProvisioner workspaceProvisioner,
            AgentRuntime agentRuntime,
            TaskExecutionContractBuilder contractBuilder,
            ToolExecutor toolExecutor,
            WorkflowScenarioResolver scenarioResolver,
            StackProfileRegistry stackProfileRegistry,
            ObjectMapper objectMapper
    ) {
        this.catalogStore = catalogStore;
        this.flowStore = flowStore;
        this.intakeStore = intakeStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.requirementStageService = requirementStageService;
        this.contextCompilationCenter = contextCompilationCenter;
        this.architectConversationAgent = architectConversationAgent;
        this.verifyDecisionAgent = verifyDecisionAgent;
        this.planningGraphMaterializer = planningGraphMaterializer;
        this.taskDispatcher = taskDispatcher;
        this.workspaceProvisioner = workspaceProvisioner;
        this.agentRuntime = agentRuntime;
        this.contractBuilder = contractBuilder;
        this.toolExecutor = toolExecutor;
        this.scenarioResolver = scenarioResolver;
        this.stackProfileRegistry = stackProfileRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> requirementNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "requirement", () -> {
            RequirementStageService.RequirementStageOutcome outcome = requirementStageService.reconcile(workflowRunId);
            return outcome.workflowStatus() == WorkflowRunStatus.WAITING_ON_HUMAN
                    ? NodeOutcome.waitingOnHuman(outcome.outputPayloadJson(), outcome.body())
                    : NodeOutcome.succeeded(outcome.outputPayloadJson(), outcome.body());
        });
    }

    @Transactional
    public Map<String, Object> architectNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "architect", () -> {
            CompiledContextPack contextPack = contextCompilationCenter.compile(new ContextCompilationRequest(
                    ContextPackType.ARCHITECT,
                    ContextScope.workflow(workflowRunId, "architect"),
                    "ARCHITECT_RECONCILE"
            ));
            AgentDefinition architectAgent = nodeAgent(workflowRunId, "architect", ARCHITECT_AGENT_ID);
            StructuredModelResult<ArchitectDecision> modelResult = architectConversationAgent.evaluate(
                    architectAgent,
                    contextPack,
                    scenarioResolver.resolveProfileRef(workflowRunId).orElseGet(stackProfileRegistry::defaultProfileRef)
            );
            ArchitectDecision decision = modelResult.value();

            if (decision.decision() == ArchitectDecisionType.NEED_INPUT) {
                Ticket ticket = createArchitectClarificationTicket(workflowRunId, architectAgent.agentId(), decision, modelResult);
                saveWorkflowStatus(workflowRunId, WorkflowRunStatus.WAITING_ON_HUMAN);
                return NodeOutcome.waitingOnHuman(
                        jsonPayload(Map.of(
                                "decision", decision.decision().name(),
                                "summary", decision.summary(),
                                "gaps", decision.gaps(),
                                "questions", decision.questions(),
                                "ticketId", ticket.ticketId(),
                                "contextPackRef", contextPack.artifactRef(),
                                "promptVersion", architectConversationAgent.promptVersion(),
                                "modelProvider", modelResult.provider(),
                                "modelName", modelResult.model()
                        )),
                        "架构阶段需要额外输入，已创建新的澄清 ticket。"
                );
            }

            resolveArchitectTickets(workflowRunId, architectAgent.agentId(), decision);
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.ACTIVE);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("decision", decision.decision().name());
            payload.put("summary", decision.summary());
            payload.put("gaps", decision.gaps());
            payload.put("questions", decision.questions());
            payload.put("planningGraph", decision.planningGraph());
            payload.put("contextPackRef", contextPack.artifactRef());
            payload.put("promptVersion", architectConversationAgent.promptVersion());
            payload.put("modelProvider", modelResult.provider());
            payload.put("modelName", modelResult.model());
            return NodeOutcome.succeeded(jsonPayload(payload), "架构节点已输出本轮规划结论。");
        });
    }

    @Transactional
    public Map<String, Object> ticketGateNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "ticket-gate", () -> {
            long pendingHumanTickets = pendingHumanTickets(workflowRunId).size();
            long pendingArchitectBlockers = openArchitectBlockingTickets(workflowRunId).size();
            if (pendingHumanTickets > 0) {
                saveWorkflowStatus(workflowRunId, WorkflowRunStatus.WAITING_ON_HUMAN);
                return NodeOutcome.waitingOnHuman(
                        jsonPayload(Map.of(
                                "pendingHumanTickets", pendingHumanTickets,
                                "pendingArchitectBlockerTickets", pendingArchitectBlockers
                        )),
                        "存在等待人类回答的 ticket，流程暂停。"
                );
            }
            if (pendingArchitectBlockers > 0) {
                saveWorkflowStatus(workflowRunId, WorkflowRunStatus.EXECUTING_TASKS);
                return NodeOutcome.succeeded(
                        jsonPayload(Map.of(
                                "pendingHumanTickets", 0,
                                "pendingArchitectBlockerTickets", pendingArchitectBlockers
                        )),
                        "存在等待架构代理收敛的 blocker ticket，本轮在 ticket gate 停驻。"
                );
            }
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.ACTIVE);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "pendingHumanTickets", 0,
                            "pendingArchitectBlockerTickets", 0
                    )),
                "工单中心确认当前无需继续等待人类输入。"
            );
        });
    }

    @Transactional
    public Map<String, Object> taskGraphNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "task-graph", () -> {
            ArchitectNodeSnapshot architectSnapshot = latestArchitectSnapshot(workflowRunId);
            int materializedCount = 0;
            if (architectSnapshot.decisionType() == ArchitectDecisionType.PLAN_READY
                    || architectSnapshot.decisionType() == ArchitectDecisionType.REPLAN_READY) {
                if (architectSnapshot.planningGraph() == null) {
                    throw new IllegalStateException("architect returned " + architectSnapshot.decisionType() + " without planning graph");
                }
                materializedCount = planningGraphMaterializer.materialize(workflowRunId, architectSnapshot.planningGraph());
            }
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "architectDecision", architectSnapshot.decisionType().name(),
                            "materializedCount", materializedCount,
                            "taskCount", planningStore.listTasksByWorkflow(workflowRunId).size()
                    )),
                    "任务图节点已根据最新架构规划物化当前 DAG。"
            );
        });
    }

    @Transactional
    public Map<String, Object> workerManagerNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "worker-manager", () -> {
            long dispatched;
            try {
                dispatched = taskDispatcher.dispatchReadyTasks().stream()
                        .filter(DispatchDecision::dispatched)
                        .filter(decision -> planningStore.findWorkflowRunIdByTask(decision.taskId())
                                .map(workflowRunId::equals)
                                .orElse(false))
                        .count();
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "worker-manager failed while dispatching ready tasks for workflow " + workflowRunId,
                        exception
                );
            }
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.EXECUTING_TASKS);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of("dispatchedTasks", dispatched)),
                    "工作代理管理器已完成中央派发。"
            );
        });
    }

    @Transactional
    public Map<String, Object> codingNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "coding", () -> {
            int blockedCount = 0;
            int deliveredCount = 0;
            int activeTaskCount = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() == WorkTaskStatus.BLOCKED) {
                    blockedCount++;
                } else if (task.status() == WorkTaskStatus.DELIVERED) {
                    deliveredCount++;
                } else if (task.status() == WorkTaskStatus.IN_PROGRESS || task.status() == WorkTaskStatus.READY) {
                    activeTaskCount++;
                }
            }
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.EXECUTING_TASKS);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "blockedCount", blockedCount,
                            "deliveredCount", deliveredCount,
                            "activeTaskCount", activeTaskCount
                    )),
                    "编码节点已根据 L4/L5 运行真相完成协调。"
            );
        });
    }

    @Transactional
    public Map<String, Object> mergeGateNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "merge-gate", () -> {
            int mergedCount = 0;
            int blockedCount = 0;
            int deliveredCount = 0;
            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.DELIVERED) {
                    continue;
                }
                deliveredCount++;
                TaskRun successfulRun = latestSuccessfulRun(task.taskId()).orElseThrow(() ->
                        new IllegalStateException("missing successful run for delivered task " + task.taskId()));
                GitWorkspace workspace = executionStore.findWorkspaceByRun(successfulRun.runId()).orElseThrow(() ->
                        new IllegalStateException("missing workspace for delivered run " + successfulRun.runId()));
                if (workspace.status() == GitWorkspaceStatus.MERGED || workspace.status() == GitWorkspaceStatus.CLEANED) {
                    continue;
                }
                try {
                    executionStore.saveWorkspace(workspaceProvisioner.mergeCandidate(workspace));
                    mergedCount++;
                } catch (RuntimeException exception) {
                    executionStore.saveWorkspace(withWorkspaceFailure(workspace));
                    planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
                    createRuntimeAlertTicket(workflowRunId, "merge-gate", task, successfulRun.runId(), failureSummary(exception));
                    blockedCount++;
                }
            }
            saveWorkflowStatus(workflowRunId, deliveredCount > 0 ? WorkflowRunStatus.VERIFYING : WorkflowRunStatus.EXECUTING_TASKS);
            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "mergedCount", mergedCount,
                            "blockedCount", blockedCount,
                            "deliveredCount", deliveredCount
                    )),
                    "合并闸门已根据真实 Git merge candidate 更新执行真相。"
            );
        });
    }

    @Transactional
    public Map<String, Object> verifyNode(PlatformWorkflowState state) {
        String workflowRunId = state.workflowRunId();
        return executeNode(workflowRunId, "verify", () -> {
            WorkflowScenario scenario = scenarioResolver.resolve(workflowRunId);
            AgentDefinition verifyAgent = nodeAgent(workflowRunId, "verify", fallbackNodeAgentId(workflowRunId, "verify"));
            int passedCount = 0;
            int reworkCount = 0;
            int blockedCount = 0;

            for (WorkTask task : planningStore.listTasksByWorkflow(workflowRunId)) {
                if (task.status() != WorkTaskStatus.DELIVERED) {
                    continue;
                }
                TaskRun successfulRun = latestSuccessfulRun(task.taskId()).orElseThrow(() ->
                        new IllegalStateException("missing successful run for delivered task " + task.taskId()));
                GitWorkspace workspace = executionStore.findWorkspaceByRun(successfulRun.runId()).orElseThrow(() ->
                        new IllegalStateException("missing workspace for delivered run " + successfulRun.runId()));
                TaskExecutionContract contract = contractBuilder.fromPayload(successfulRun.executionContractJson());
                Path verifyCheckout = null;
                try {
                    verifyCheckout = workspaceProvisioner.checkoutMergeCandidate(workspace);
                    ToolExecutor.ToolExecutionOutcome deterministicOutcome = toolExecutor.executeVerifyPlan(
                            successfulRun,
                            contract,
                            verifyCheckout
                    );
                    executionStore.appendTaskRunEvent(new TaskRunEvent(
                            eventId("task-run"),
                            successfulRun.runId(),
                            "VERIFY_DETERMINISTIC_COMPLETED",
                            deterministicOutcome.succeeded() ? "deterministic verify passed" : "deterministic verify failed",
                            jsonPayload(verifyDeterministicPayload(task.taskId(), workflowRunId, deterministicOutcome.payload()))
                    ));

                    CompiledContextPack contextPack = contextCompilationCenter.compile(new ContextCompilationRequest(
                            ContextPackType.VERIFY,
                            ContextScope.task(workflowRunId, task.taskId(), successfulRun.runId(), "verify", verifyCheckout),
                            "VERIFY_RECONCILE"
                    ));
                    StructuredModelResult<VerifyDecision> modelResult = verifyDecisionAgent.evaluate(
                            verifyAgent,
                            contextPack,
                            scenarioResolver.resolveProfileRef(workflowRunId).orElseGet(stackProfileRegistry::defaultProfileRef)
                    );
                    VerifyDecision decision = normalizeVerifyDecision(
                            modelResult.value(),
                            deterministicOutcome.succeeded(),
                            scenario,
                            executionStore.listTaskRuns(task.taskId()).size()
                    );
                    executionStore.appendTaskRunEvent(new TaskRunEvent(
                            eventId("task-run"),
                            successfulRun.runId(),
                            "VERIFY_DECISION_MADE",
                            decision.summary(),
                            jsonPayload(verifyDecisionPayload(decision, contextPack.artifactRef(), modelResult))
                    ));

                    switch (decision.decision()) {
                        case PASS -> {
                            // A delivered run only becomes DONE after verify accepts both the deterministic evidence
                            // and the verify-agent decision. Successful run or merged workspace alone is not enough.
                            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.DONE));
                            passedCount++;
                        }
                        case REWORK -> {
                            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.READY));
                            reworkCount++;
                        }
                        case ESCALATE -> {
                            planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
                            createVerifyEscalationTicket(workflowRunId, verifyAgent.agentId(), task, successfulRun.runId(), decision);
                            blockedCount++;
                        }
                    }
                    executionStore.saveWorkspace(workspaceProvisioner.cleanup(workspace));
                } catch (RuntimeException exception) {
                    planningStore.saveTask(withTaskStatus(task, WorkTaskStatus.BLOCKED));
                    executionStore.saveWorkspace(workspaceProvisioner.cleanup(withWorkspaceFailure(workspace)));
                    createRuntimeAlertTicket(workflowRunId, "verify", task, successfulRun.runId(), failureSummary(exception));
                    blockedCount++;
                } finally {
                    workspaceProvisioner.releaseCheckout(verifyCheckout);
                }
            }

            WorkflowRunStatus finalStatus = planningStore.listTasksByWorkflow(workflowRunId).stream()
                    .allMatch(task -> task.status() == WorkTaskStatus.DONE)
                    ? WorkflowRunStatus.COMPLETED
                    : WorkflowRunStatus.EXECUTING_TASKS;
            saveWorkflowStatus(workflowRunId, finalStatus);

            return NodeOutcome.succeeded(
                    jsonPayload(Map.of(
                            "passedCount", passedCount,
                            "reworkCount", reworkCount,
                            "blockedCount", blockedCount
                    )),
                    "验证节点已完成当前交付候选检查。"
            );
        });
    }

    public String routeAfterArchitect(PlatformWorkflowState state) {
        return pendingHumanTickets(state.workflowRunId()).isEmpty()
                && openArchitectBlockingTickets(state.workflowRunId()).isEmpty()
                ? "task-graph"
                : "ticket-gate";
    }

    public String routeAfterRequirement(PlatformWorkflowState state) {
        return requirementStageService.isRequirementConfirmed(state.workflowRunId()) ? "architect" : "ticket-gate";
    }

    public String routeAfterTicketGate(PlatformWorkflowState state) {
        if (!pendingHumanTickets(state.workflowRunId()).isEmpty()
                || !openArchitectBlockingTickets(state.workflowRunId()).isEmpty()) {
            return "end";
        }
        return requirementStageService.isRequirementConfirmed(state.workflowRunId()) ? "architect" : "requirement";
    }

    public String routeAfterCoding(PlatformWorkflowState state) {
        List<WorkTask> tasks = planningStore.listTasksByWorkflow(state.workflowRunId());
        if (tasks.stream().anyMatch(task -> task.status() == WorkTaskStatus.BLOCKED)) {
            return "architect";
        }
        if (tasks.stream().anyMatch(task -> task.status() == WorkTaskStatus.DELIVERED)) {
            return "merge-gate";
        }
        return "end";
    }

    public String routeAfterVerify(PlatformWorkflowState state) {
        List<WorkTask> tasks = planningStore.listTasksByWorkflow(state.workflowRunId());
        if (tasks.stream().allMatch(task -> task.status() == WorkTaskStatus.DONE)) {
            return "end";
        }
        if (tasks.stream().anyMatch(task -> task.status() == WorkTaskStatus.READY || task.status() == WorkTaskStatus.BLOCKED)) {
            return "architect";
        }
        return "end";
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
                    jsonPayload(errorPayload(exception)),
                    startedNodeRun.startedAt(),
                    now()
            );
            flowStore.saveNodeRun(failedNodeRun);
            flowStore.appendNodeRunEvent(new WorkflowNodeRunEvent(
                    eventId("node-run"),
                    failedNodeRun.nodeRunId(),
                    "NODE_FAILED",
                    failureSummary(exception),
                    failedNodeRun.outputPayloadJson()
            ));
            saveWorkflowStatus(workflowRunId, WorkflowRunStatus.FAILED);
            throw exception;
        }
    }

    private AgentDefinition nodeAgent(String workflowRunId, String nodeId, String fallbackAgentId) {
        String agentId = selectedAgentId(workflowRunId, nodeId);
        if (agentId == null || agentId.isBlank()) {
            agentId = fallbackAgentId;
        }
        String resolvedAgentId = agentId;
        return catalogStore.findAgent(resolvedAgentId)
                .orElseThrow(() -> new IllegalStateException("agent definition not found for node " + nodeId + ": " + resolvedAgentId));
    }

    private String fallbackNodeAgentId(String workflowRunId, String nodeId) {
        ActiveStackProfileSnapshot activeProfile = scenarioResolver.resolveProfileRef(workflowRunId)
                .map(WorkflowProfileRef::profileId)
                .map(stackProfileRegistry::resolveRequired)
                .orElseGet(() -> stackProfileRegistry.resolveRequired(StackProfileRegistry.DEFAULT_PROFILE_ID));
        String agentId = activeProfile.nodeAgentId(nodeId);
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalStateException("stack profile " + activeProfile.profileId() + " does not define node agent for " + nodeId);
        }
        return agentId;
    }

    private Ticket createArchitectClarificationTicket(
            String workflowRunId,
            String agentId,
            ArchitectDecision decision,
            StructuredModelResult<ArchitectDecision> modelResult
    ) {
        ActorRef actor = new ActorRef(ActorType.AGENT, agentId);
        Optional<Ticket> triggerTicket = architectRelevantTickets(workflowRunId).stream()
                .filter(ticket -> ticket.status() != TicketStatus.RESOLVED && ticket.status() != TicketStatus.CANCELED)
                .reduce((left, right) -> right);
        cancelArchitectTickets(workflowRunId, actor, "架构代理已生成新的澄清问题，旧票据已被替换。");

        String taskId = triggerTicket.map(Ticket::taskId).orElse(null);
        TicketBlockingScope blockingScope = taskId == null
                ? TicketBlockingScope.GLOBAL_BLOCKING
                : TicketBlockingScope.TASK_BLOCKING;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", "ARCHITECT_CLARIFICATION");
        payload.put("gaps", decision.gaps());
        payload.put("questions", decision.questions());
        payload.put("summary", decision.summary());
        payload.put("triggerTicketId", triggerTicket.map(Ticket::ticketId).orElse(null));
        payload.put("modelProvider", modelResult.provider());
        payload.put("modelName", modelResult.model());
        if (taskId != null) {
            payload.put("taskId", taskId);
        }
        Ticket ticket = new Ticket(
                "ticket-architect-" + shortToken(workflowRunId, UUID.randomUUID().toString()),
                workflowRunId,
                TicketType.CLARIFICATION,
                blockingScope,
                TicketStatus.OPEN,
                taskId == null ? "架构阶段需要更多全局事实" : "架构阶段需要补充任务事实",
                actor,
                workflowOwner(workflowRunId),
                "architect",
                null,
                null,
                taskId,
                jsonPayload(payload)
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "ARCHITECT_CLARIFICATION_REQUESTED",
                actor,
                decision.summary(),
                ticket.payloadJson()
        ));
        return ticket;
    }

    private void cancelArchitectTickets(String workflowRunId, ActorRef actor, String body) {
        for (Ticket ticket : architectRelevantTickets(workflowRunId)) {
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CANCELED) {
                continue;
            }
            Ticket canceledTicket = new Ticket(
                    ticket.ticketId(),
                    ticket.workflowRunId(),
                    ticket.type(),
                    ticket.blockingScope(),
                    TicketStatus.CANCELED,
                    ticket.title(),
                    ticket.createdBy(),
                    actor,
                    ticket.originNodeId(),
                    ticket.requirementDocId(),
                    ticket.requirementDocVersion(),
                    ticket.taskId(),
                    ticket.payloadJson()
            );
            intakeStore.saveTicket(canceledTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    canceledTicket.ticketId(),
                    "ARCHITECT_TICKET_CANCELED",
                    actor,
                    body,
                    canceledTicket.payloadJson()
            ));
        }
    }

    private void resolveArchitectTickets(String workflowRunId, String agentId, ArchitectDecision decision) {
        ActorRef actor = new ActorRef(ActorType.AGENT, agentId);
        for (Ticket ticket : architectRelevantTickets(workflowRunId)) {
            if (ticket.status() == TicketStatus.RESOLVED || ticket.status() == TicketStatus.CANCELED) {
                continue;
            }
            if (!shouldResolveArchitectTicket(ticket, decision.decision())) {
                continue;
            }
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
                    ticket.taskId(),
                    ticket.payloadJson()
            );
            intakeStore.saveTicket(resolvedTicket);
            intakeStore.appendTicketEvent(new TicketEvent(
                    eventId("ticket"),
                    resolvedTicket.ticketId(),
                    "ARCHITECT_TICKET_RESOLVED",
                    actor,
                    decision.summary(),
                    resolvedTicket.payloadJson()
            ));

            String taskId = ticket.taskId();
            if (taskId == null) {
                continue;
            }
            planningStore.findTask(taskId)
                    .filter(task -> shouldReopenBlockedTask(ticket, decision.decision()))
                    .filter(task -> task.status() == WorkTaskStatus.BLOCKED)
                    .map(task -> withTaskStatus(task, WorkTaskStatus.READY))
                    .ifPresent(planningStore::saveTask);
        }
    }

    private ArchitectNodeSnapshot latestArchitectSnapshot(String workflowRunId) {
        WorkflowNodeRun architectNodeRun = flowStore.listNodeRuns(workflowRunId).stream()
                .filter(run -> "architect".equals(run.nodeId()))
                .filter(run -> run.outputPayloadJson() != null)
                .reduce((left, right) -> right)
                .orElseThrow(() -> new IllegalStateException("missing architect node snapshot for workflow " + workflowRunId));
        Map<String, Object> payload = payloadMap(architectNodeRun.outputPayloadJson());
        ArchitectDecisionType decisionType = ArchitectDecisionType.valueOf(
                String.valueOf(payload.getOrDefault("decision", ArchitectDecisionType.NO_CHANGES.name()))
        );
        PlanningGraphSpec planningGraph = payload.containsKey("planningGraph") && payload.get("planningGraph") != null
                ? objectMapper.convertValue(payload.get("planningGraph"), PlanningGraphSpec.class)
                : null;
        return new ArchitectNodeSnapshot(decisionType, planningGraph);
    }

    private void createVerifyEscalationTicket(
            String workflowRunId,
            String verifyAgentId,
            WorkTask task,
            String runId,
            VerifyDecision decision
    ) {
        Map<String, Object> payloadData = new LinkedHashMap<>();
        payloadData.put("taskId", task.taskId());
        payloadData.put("runId", runId);
        payloadData.put("summary", decision.summary());
        payloadData.put("detail", decision.escalationBody());
        JsonPayload payload = jsonPayload(payloadData);
        Ticket ticket = new Ticket(
                "ticket-verify-" + shortToken(task.taskId(), runId, UUID.randomUUID().toString()),
                workflowRunId,
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                decision.escalationTitle() == null ? "验证阶段需要架构代理介入" : decision.escalationTitle(),
                new ActorRef(ActorType.AGENT, verifyAgentId),
                new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
                "verify",
                null,
                null,
                task.taskId(),
                payload
        );
        intakeStore.saveTicket(ticket);
        intakeStore.appendTicketEvent(new TicketEvent(
                eventId("ticket"),
                ticket.ticketId(),
                "VERIFY_ESCALATION_CREATED",
                new ActorRef(ActorType.AGENT, verifyAgentId),
                decision.summary(),
                payload
        ));
    }

    private VerifyDecision normalizeVerifyDecision(
            VerifyDecision decision,
            boolean deterministicSucceeded,
            WorkflowScenario scenario,
            int attemptCount
    ) {
        if (!deterministicSucceeded) {
            return new VerifyDecision(
                    VerifyDecisionType.REWORK,
                    "deterministic verify failed, task must return for rework",
                    decision.escalationTitle(),
                    decision.escalationBody()
            );
        }
        if (scenario.verifyNeedsRework() && attemptCount == 1 && decision.decision() == VerifyDecisionType.PASS) {
            return new VerifyDecision(
                    VerifyDecisionType.REWORK,
                    "scenario requested one explicit rework cycle before acceptance",
                    decision.escalationTitle(),
                    decision.escalationBody()
            );
        }
        return decision;
    }

    private void createRuntimeAlertTicket(String workflowRunId, String originNodeId, WorkTask task, String runId, String reason) {
        Map<String, Object> payloadData = new LinkedHashMap<>();
        payloadData.put("taskId", task.taskId());
        payloadData.put("runId", runId);
        payloadData.put("reason", reason);
        String body = reason == null || reason.isBlank() ? "runtime infrastructure failure" : reason;
        JsonPayload payload = jsonPayload(payloadData);
        Ticket ticket = new Ticket(
                "ticket-runtime-" + shortToken(task.taskId(), String.valueOf(runId), UUID.randomUUID().toString()),
                workflowRunId,
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "运行基础设施失败需要架构代理处理",
                new ActorRef(ActorType.SYSTEM, "runtime"),
                new ActorRef(ActorType.AGENT, ARCHITECT_AGENT_ID),
                originNodeId,
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
                new ActorRef(ActorType.SYSTEM, "runtime"),
                body,
                payload
        ));
    }

    private List<Ticket> pendingHumanTickets(String workflowRunId) {
        return intakeStore.listOpenTickets(workflowRunId).stream()
                .filter(ticket -> ticket.assignee().type() == ActorType.HUMAN && ticket.status() == TicketStatus.OPEN)
                .toList();
    }

    private List<Ticket> openArchitectBlockingTickets(String workflowRunId) {
        return intakeStore.listOpenTickets(workflowRunId).stream()
                .filter(ticket -> ticket.status() == TicketStatus.OPEN)
                .filter(this::assignedToArchitectAgent)
                .filter(ticket -> ticket.blockingScope() != TicketBlockingScope.INFORMATIONAL)
                .toList();
    }

    private List<Ticket> architectRelevantTickets(String workflowRunId) {
        return intakeStore.listTicketsForWorkflow(workflowRunId).stream()
                .filter(ticket -> "architect".equals(ticket.originNodeId()) || assignedToArchitectAgent(ticket))
                .toList();
    }

    private boolean assignedToArchitectAgent(Ticket ticket) {
        return ticket.assignee().type() == ActorType.AGENT && ARCHITECT_AGENT_ID.equals(ticket.assignee().actorId());
    }

    private boolean shouldResolveArchitectTicket(Ticket ticket, ArchitectDecisionType decisionType) {
        return switch (ticket.type()) {
            case CLARIFICATION, DECISION -> true;
            case ALERT -> decisionType == ArchitectDecisionType.PLAN_READY || decisionType == ArchitectDecisionType.REPLAN_READY;
        };
    }

    private boolean shouldReopenBlockedTask(Ticket ticket, ArchitectDecisionType decisionType) {
        if (ticket.taskId() == null) {
            return false;
        }
        return switch (ticket.type()) {
            case CLARIFICATION, DECISION -> true;
            case ALERT -> decisionType == ArchitectDecisionType.PLAN_READY || decisionType == ArchitectDecisionType.REPLAN_READY;
        };
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

    private WorkflowRun workflow(String workflowRunId) {
        return flowStore.findRun(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + workflowRunId));
    }

    private ActorRef workflowOwner(String workflowRunId) {
        WorkflowRun workflowRun = workflow(workflowRunId);
        if (workflowRun.createdBy().type() == ActorType.HUMAN) {
            return workflowRun.createdBy();
        }
        return new ActorRef(ActorType.HUMAN, "workflow-owner");
    }

    private String selectedAgentId(String workflowRunId, String nodeId) {
        return flowStore.listNodeBindings(workflowRunId).stream()
                .filter(binding -> binding.nodeId().equals(nodeId))
                .map(binding -> binding.selectedAgentId())
                .findFirst()
                .orElse(null);
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

    private Map<String, Object> payloadMap(JsonPayload payload) {
        if (payload == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload.json(), MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to parse json payload", exception);
        }
    }

    private JsonPayload jsonPayload(Map<String, Object> data) {
        try {
            return new JsonPayload(objectMapper.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to write json payload", exception);
        }
    }

    private Map<String, Object> errorPayload(RuntimeException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", failureSummary(exception));
        if (exception.getCause() != null) {
            payload.put("causeType", exception.getCause().getClass().getName());
            payload.put("causeMessage", exception.getCause().getMessage());
        }
        return payload;
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

    private String payloadString(JsonPayload payload, String key) {
        Object value = payloadMap(payload).get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> verifyDeterministicPayload(
            String taskId,
            String workflowRunId,
            JsonPayload deterministicPayload
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("workflowRunId", workflowRunId);
        payload.put("deterministicEvidence", payloadMap(deterministicPayload));
        return payload;
    }

    private Map<String, Object> verifyDecisionPayload(
            VerifyDecision decision,
            String contextPackRef,
            StructuredModelResult<VerifyDecision> modelResult
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", decision.decision().name());
        payload.put("summary", decision.summary());
        payload.put("escalationTitle", decision.escalationTitle());
        payload.put("escalationBody", decision.escalationBody());
        payload.put("contextPackRef", contextPackRef);
        payload.put("promptVersion", verifyDecisionAgent.promptVersion());
        payload.put("modelProvider", modelResult.provider());
        payload.put("modelName", modelResult.model());
        return payload;
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private String nodeRunId(String nodeId) {
        return nodeId + "-node-run-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private record ArchitectNodeSnapshot(
            ArchitectDecisionType decisionType,
            PlanningGraphSpec planningGraph
    ) {
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

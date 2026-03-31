package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecision;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectDecisionType;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionAgent;
import com.agentx.platform.runtime.application.workflow.FixedCodingNodeExecutor;
import com.agentx.platform.runtime.application.workflow.PlanningGraphMaterializer;
import com.agentx.platform.runtime.application.workflow.RequirementStageService;
import com.agentx.platform.runtime.application.workflow.TaskDispatcher;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContractBuilder;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.runtime.context.CompiledContextPack;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.context.ContextPackType;
import com.agentx.platform.runtime.context.ContextScope;
import com.agentx.platform.runtime.context.FactBundle;
import com.agentx.platform.runtime.context.RetrievalBundle;
import com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedCodingNodeExecutorArchitectTicketTests {

    @Test
    void shouldKeepRuntimeAlertOpenWhenArchitectReturnsNoChanges() {
        Fixture fixture = new Fixture();
        Ticket alertTicket = fixture.openTicket(TicketType.ALERT, "task-1");
        when(fixture.intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(alertTicket));
        when(fixture.architectConversationAgent.evaluate(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                new ArchitectDecision(ArchitectDecisionType.NO_CHANGES, List.of(), List.of(), "no task graph change", null),
                "deepseek",
                "gpt-5.4",
                "{}"
        ));

        fixture.executor().architectNode(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1")));

        verify(fixture.intakeStore, never()).saveTicket(argThat(ticket ->
                ticket.ticketId().equals(alertTicket.ticketId()) && ticket.status() == TicketStatus.RESOLVED));
        verify(fixture.planningStore, never()).saveTask(any());
    }

    @Test
    void shouldResolveClarificationTicketAndReopenTaskWhenArchitectReturnsNoChanges() {
        Fixture fixture = new Fixture();
        Ticket clarificationTicket = fixture.openTicket(TicketType.CLARIFICATION, "task-1");
        when(fixture.intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(clarificationTicket));
        when(fixture.planningStore.findTask("task-1")).thenReturn(Optional.of(fixture.blockedTask("task-1")));
        when(fixture.architectConversationAgent.evaluate(any(), any(), any())).thenReturn(new StructuredModelResult<>(
                new ArchitectDecision(ArchitectDecisionType.NO_CHANGES, List.of(), List.of(), "task facts are sufficient", null),
                "deepseek",
                "gpt-5.4",
                "{}"
        ));

        fixture.executor().architectNode(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1")));

        verify(fixture.intakeStore).saveTicket(argThat(ticket ->
                ticket.ticketId().equals(clarificationTicket.ticketId()) && ticket.status() == TicketStatus.RESOLVED));
        verify(fixture.planningStore).saveTask(argThat(task ->
                task.taskId().equals("task-1") && task.status() == WorkTaskStatus.READY));
    }

    private static final class Fixture {

        private final CatalogStore catalogStore = mock(CatalogStore.class);
        private final FlowStore flowStore = mock(FlowStore.class);
        private final IntakeStore intakeStore = mock(IntakeStore.class);
        private final PlanningStore planningStore = mock(PlanningStore.class);
        private final ExecutionStore executionStore = mock(ExecutionStore.class);
        private final RequirementStageService requirementStageService = mock(RequirementStageService.class);
        private final ContextCompilationCenter contextCompilationCenter = mock(ContextCompilationCenter.class);
        private final ArchitectConversationAgent architectConversationAgent = mock(ArchitectConversationAgent.class);
        private final VerifyDecisionAgent verifyDecisionAgent = mock(VerifyDecisionAgent.class);
        private final PlanningGraphMaterializer planningGraphMaterializer = mock(PlanningGraphMaterializer.class);
        private final TaskDispatcher taskDispatcher = mock(TaskDispatcher.class);
        private final WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        private final AgentRuntime agentRuntime = mock(AgentRuntime.class);
        private final TaskExecutionContractBuilder contractBuilder = mock(TaskExecutionContractBuilder.class);
        private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
        private final WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        private final ObjectMapper objectMapper = new ObjectMapper();

        private Fixture() {
            when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(new WorkflowRun(
                    "workflow-1",
                    "builtin-coding-flow",
                    "workflow",
                    WorkflowRunStatus.ACTIVE,
                    EntryMode.MANUAL,
                    false,
                    new ActorRef(ActorType.HUMAN, "tester")
            )));
            when(flowStore.listNodeBindings("workflow-1")).thenReturn(List.of());
            when(scenarioResolver.resolveProfileRef("workflow-1")).thenReturn(Optional.of(TestStackProfiles.defaultProfileRef()));
            when(catalogStore.findAgent("architect-agent")).thenReturn(Optional.of(new AgentDefinition(
                    "architect-agent",
                    "Architect Agent",
                    "plan work",
                    "SYSTEM",
                    "in-process",
                    "gpt-5.4",
                    4,
                    true,
                    true,
                    true,
                    true
            )));
            when(contextCompilationCenter.compile(any())).thenReturn(new CompiledContextPack(
                    ContextPackType.ARCHITECT,
                    ContextScope.workflow("workflow-1", "architect"),
                    "fingerprint",
                    "artifact-ref",
                    "{}",
                    new FactBundle(Map.of()),
                    new RetrievalBundle(List.of()),
                    LocalDateTime.now()
            ));
            when(architectConversationAgent.promptVersion()).thenReturn("architect-v1");
        }

        private FixedCodingNodeExecutor executor() {
            return new FixedCodingNodeExecutor(
                    catalogStore,
                    flowStore,
                    intakeStore,
                    planningStore,
                    executionStore,
                    requirementStageService,
                    contextCompilationCenter,
                    architectConversationAgent,
                    verifyDecisionAgent,
                    planningGraphMaterializer,
                    taskDispatcher,
                    workspaceProvisioner,
                    agentRuntime,
                    contractBuilder,
                    toolExecutor,
                    scenarioResolver,
                    TestStackProfiles.registry(),
                    objectMapper
            );
        }

        private Ticket openTicket(TicketType type, String taskId) {
            return new Ticket(
                    "ticket-1",
                    "workflow-1",
                    type,
                    TicketBlockingScope.TASK_BLOCKING,
                    TicketStatus.OPEN,
                    "needs architect",
                    new ActorRef(ActorType.SYSTEM, "runtime"),
                    new ActorRef(ActorType.AGENT, "architect-agent"),
                    "coding",
                    null,
                    null,
                    taskId,
                    JsonPayload.emptyObject()
            );
        }

        private WorkTask blockedTask(String taskId) {
            return new WorkTask(
                    taskId,
                    "module-1",
                    "task",
                    "objective",
                    "java-backend-code",
                    WorkTaskStatus.BLOCKED,
                    List.of(new WriteScope("src/main/java")),
                    null,
                    new ActorRef(ActorType.AGENT, "architect-agent")
            );
        }
    }
}

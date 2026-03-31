package com.agentx.platform;

import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentkernel.architect.ArchitectConversationAgent;
import com.agentx.platform.runtime.agentkernel.verify.VerifyDecisionAgent;
import com.agentx.platform.runtime.application.workflow.FixedCodingNodeExecutor;
import com.agentx.platform.runtime.application.workflow.PlanningGraphMaterializer;
import com.agentx.platform.runtime.application.workflow.RequirementStageService;
import com.agentx.platform.runtime.application.workflow.TaskDispatcher;
import com.agentx.platform.runtime.application.workflow.TaskExecutionContractBuilder;
import com.agentx.platform.runtime.application.workflow.WorkflowScenarioResolver;
import com.agentx.platform.runtime.context.ContextCompilationCenter;
import com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState;
import com.agentx.platform.runtime.tooling.ToolExecutor;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.agentx.platform.support.TestStackProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphRoutingTests {

    @Test
    void shouldRouteBackToRequirementWhenRequirementIsNotConfirmed() {
        IntakeStore intakeStore = mock(IntakeStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        when(intakeStore.listOpenTickets("workflow-1")).thenReturn(List.of());
        when(requirementStageService.isRequirementConfirmed("workflow-1")).thenReturn(false);

        FixedCodingNodeExecutor nodeExecutor = nodeExecutor(intakeStore, requirementStageService);

        assertThat(nodeExecutor.routeAfterTicketGate(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1"))))
                .isEqualTo("requirement");
    }

    @Test
    void shouldRouteToArchitectWhenRequirementIsConfirmed() {
        IntakeStore intakeStore = mock(IntakeStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        when(intakeStore.listOpenTickets("workflow-1")).thenReturn(List.of());
        when(requirementStageService.isRequirementConfirmed("workflow-1")).thenReturn(true);

        FixedCodingNodeExecutor nodeExecutor = nodeExecutor(intakeStore, requirementStageService);

        assertThat(nodeExecutor.routeAfterRequirement(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1"))))
                .isEqualTo("architect");
        assertThat(nodeExecutor.routeAfterTicketGate(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1"))))
                .isEqualTo("architect");
    }

    @Test
    void shouldPauseAfterArchitectWhenOpenArchitectBlockerStillExists() {
        IntakeStore intakeStore = mock(IntakeStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        when(intakeStore.listOpenTickets("workflow-1")).thenReturn(List.of(openArchitectAlert("workflow-1")));

        FixedCodingNodeExecutor nodeExecutor = nodeExecutor(intakeStore, requirementStageService);

        assertThat(nodeExecutor.routeAfterArchitect(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1"))))
                .isEqualTo("ticket-gate");
    }

    @Test
    void shouldEndAtTicketGateWhenArchitectBlockerRemainsOpen() {
        IntakeStore intakeStore = mock(IntakeStore.class);
        RequirementStageService requirementStageService = mock(RequirementStageService.class);
        when(intakeStore.listOpenTickets("workflow-1")).thenReturn(List.of(openArchitectAlert("workflow-1")));
        when(requirementStageService.isRequirementConfirmed("workflow-1")).thenReturn(true);

        FixedCodingNodeExecutor nodeExecutor = nodeExecutor(intakeStore, requirementStageService);

        assertThat(nodeExecutor.routeAfterTicketGate(new PlatformWorkflowState(Map.of("workflowRunId", "workflow-1"))))
                .isEqualTo("end");
    }

    private FixedCodingNodeExecutor nodeExecutor(IntakeStore intakeStore, RequirementStageService requirementStageService) {
        WorkflowScenarioResolver scenarioResolver = mock(WorkflowScenarioResolver.class);
        when(scenarioResolver.resolveProfileRef("workflow-1")).thenReturn(java.util.Optional.of(TestStackProfiles.defaultProfileRef()));
        return new FixedCodingNodeExecutor(
                mock(CatalogStore.class),
                mock(FlowStore.class),
                intakeStore,
                mock(PlanningStore.class),
                mock(ExecutionStore.class),
                requirementStageService,
                mock(ContextCompilationCenter.class),
                mock(ArchitectConversationAgent.class),
                mock(VerifyDecisionAgent.class),
                mock(PlanningGraphMaterializer.class),
                mock(TaskDispatcher.class),
                mock(WorkspaceProvisioner.class),
                mock(AgentRuntime.class),
                mock(TaskExecutionContractBuilder.class),
                mock(ToolExecutor.class),
                scenarioResolver,
                TestStackProfiles.registry(),
                new ObjectMapper()
        );
    }

    private Ticket openArchitectAlert(String workflowRunId) {
        return new Ticket(
                "ticket-runtime-1",
                workflowRunId,
                TicketType.ALERT,
                TicketBlockingScope.TASK_BLOCKING,
                TicketStatus.OPEN,
                "运行失败需要架构代理处理",
                new ActorRef(ActorType.SYSTEM, "runtime"),
                new ActorRef(ActorType.AGENT, "architect-agent"),
                "coding",
                null,
                null,
                "task-1",
                JsonPayload.emptyObject()
        );
    }
}

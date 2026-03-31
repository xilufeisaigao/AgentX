package com.agentx.platform;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowBindingMode;
import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunEvent;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.agentkernel.model.StructuredModelResult;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementAgentDecision;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementConversationAgent;
import com.agentx.platform.runtime.agentkernel.requirement.RequirementDecisionType;
import com.agentx.platform.runtime.application.workflow.ConfirmRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.EditRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.RequirementStageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequirementStageServiceTests {

    private FlowStore flowStore;
    private IntakeStore intakeStore;
    private CatalogStore catalogStore;
    private RequirementConversationAgent requirementConversationAgent;
    private RequirementStageService requirementStageService;

    @BeforeEach
    void setUp() {
        flowStore = mock(FlowStore.class);
        intakeStore = mock(IntakeStore.class);
        catalogStore = mock(CatalogStore.class);
        requirementConversationAgent = mock(RequirementConversationAgent.class);
        requirementStageService = new RequirementStageService(
                flowStore,
                intakeStore,
                catalogStore,
                requirementConversationAgent,
                new ObjectMapper()
        );
        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(workflow("workflow-1", WorkflowRunStatus.ACTIVE)));
        when(flowStore.listRunEvents("workflow-1")).thenReturn(List.of(startEvent("workflow-1")));
        when(flowStore.listNodeBindings("workflow-1")).thenReturn(List.of(requirementBinding("workflow-1")));
        when(catalogStore.findAgent("requirement-agent")).thenReturn(Optional.of(requirementAgent()));
    }

    @Test
    void shouldOpenClarificationTicketWithoutCreatingRequirementDoc() {
        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.empty());
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of());
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(new StructuredModelResult<>(
                new RequirementAgentDecision(
                        RequirementDecisionType.NEED_INPUT,
                        List.of("缺少验收标准"),
                        List.of("登录失败时展示什么提示？"),
                        null,
                        null,
                        "still missing"
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"NEED_INPUT\"}"
        ));

        RequirementStageService.RequirementStageOutcome outcome = requirementStageService.reconcile("workflow-1");

        assertThat(outcome.workflowStatus()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);
        verify(intakeStore, never()).saveRequirement(any(RequirementDoc.class));
        verify(intakeStore, never()).appendRequirementVersion(any(RequirementVersion.class));

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().type()).isEqualTo(TicketType.CLARIFICATION);
        assertThat(ticketCaptor.getValue().blockingScope()).isEqualTo(TicketBlockingScope.GLOBAL_BLOCKING);
        assertThat(ticketCaptor.getValue().requirementDocId()).isNull();
    }

    @Test
    void shouldCreateFirstDraftAndConfirmationTicketWhenInformationIsEnough() {
        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.empty());
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of());
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(new StructuredModelResult<>(
                new RequirementAgentDecision(
                        RequirementDecisionType.DRAFT_READY,
                        List.of(),
                        List.of(),
                        "用户登录",
                        "## 目标\n支持邮箱密码登录",
                        "draft is ready"
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"DRAFT_READY\"}"
        ));

        RequirementStageService.RequirementStageOutcome outcome = requirementStageService.reconcile("workflow-1");

        assertThat(outcome.workflowStatus()).isEqualTo(WorkflowRunStatus.WAITING_ON_HUMAN);

        ArgumentCaptor<RequirementDoc> docCaptor = ArgumentCaptor.forClass(RequirementDoc.class);
        verify(intakeStore).saveRequirement(docCaptor.capture());
        assertThat(docCaptor.getValue().status()).isEqualTo(RequirementStatus.IN_REVIEW);
        assertThat(docCaptor.getValue().currentVersion()).isEqualTo(1);
        assertThat(docCaptor.getValue().confirmedVersion()).isNull();

        ArgumentCaptor<RequirementVersion> versionCaptor = ArgumentCaptor.forClass(RequirementVersion.class);
        verify(intakeStore).appendRequirementVersion(versionCaptor.capture());
        assertThat(versionCaptor.getValue().version()).isEqualTo(1);
        assertThat(versionCaptor.getValue().createdBy().type()).isEqualTo(ActorType.AGENT);

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().type()).isEqualTo(TicketType.DECISION);
        assertThat(ticketCaptor.getValue().requirementDocVersion()).isEqualTo(1);
    }

    @Test
    void shouldAppendNewVersionWhenRequirementFeedbackRequestsRevision() {
        RequirementDoc doc = new RequirementDoc("requirement-workflow-1", "workflow-1", 1, null, RequirementStatus.IN_REVIEW, "旧标题");
        RequirementVersion currentVersion = new RequirementVersion(
                doc.docId(),
                1,
                "旧内容",
                new ActorRef(ActorType.AGENT, "requirement-agent")
        );
        Ticket answeredDecisionTicket = new Ticket(
                "ticket-1",
                "workflow-1",
                TicketType.DECISION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.ANSWERED,
                "需求文档待确认",
                new ActorRef(ActorType.AGENT, "requirement-agent"),
                new ActorRef(ActorType.HUMAN, "user-1"),
                "requirement",
                doc.docId(),
                1,
                payload(Map.of(
                        "phase", "CONFIRMATION",
                        "answer", "请补充登录失败时的提示文案"
                ))
        );

        when(intakeStore.findRequirementByWorkflow("workflow-1")).thenReturn(Optional.of(doc));
        when(intakeStore.listRequirementVersions(doc.docId())).thenReturn(List.of(currentVersion));
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(answeredDecisionTicket));
        when(requirementConversationAgent.evaluate(any(), any())).thenReturn(new StructuredModelResult<>(
                new RequirementAgentDecision(
                        RequirementDecisionType.DRAFT_READY,
                        List.of(),
                        List.of(),
                        "新标题",
                        "新内容",
                        "revised"
                ),
                "stub",
                "deepseek-chat",
                "{\"decision\":\"DRAFT_READY\"}"
        ));

        requirementStageService.reconcile("workflow-1");

        ArgumentCaptor<RequirementVersion> versionCaptor = ArgumentCaptor.forClass(RequirementVersion.class);
        verify(intakeStore).appendRequirementVersion(versionCaptor.capture());
        assertThat(versionCaptor.getValue().version()).isEqualTo(2);
        assertThat(versionCaptor.getValue().content()).isEqualTo("新内容");

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore, org.mockito.Mockito.times(2)).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues())
                .extracting(Ticket::status)
                .containsExactly(TicketStatus.RESOLVED, TicketStatus.OPEN);
    }

    @Test
    void shouldAppendHumanEditedVersionAndClearConfirmationState() {
        RequirementDoc doc = new RequirementDoc("requirement-workflow-1", "workflow-1", 2, 2, RequirementStatus.CONFIRMED, "旧标题");
        Ticket confirmationTicket = new Ticket(
                "ticket-1",
                "workflow-1",
                TicketType.DECISION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.OPEN,
                "需求文档待确认",
                new ActorRef(ActorType.AGENT, "requirement-agent"),
                new ActorRef(ActorType.HUMAN, "user-1"),
                "requirement",
                doc.docId(),
                2,
                payload(Map.of("phase", "CONFIRMATION"))
        );

        when(intakeStore.findRequirement(doc.docId())).thenReturn(Optional.of(doc));
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(confirmationTicket));
        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(workflow("workflow-1", WorkflowRunStatus.WAITING_ON_HUMAN)));

        String workflowRunId = requirementStageService.editRequirementDoc(new EditRequirementDocCommand(
                doc.docId(),
                "人工修订标题",
                "人工修订内容",
                new ActorRef(ActorType.HUMAN, "editor-1")
        ));

        assertThat(workflowRunId).isEqualTo("workflow-1");

        ArgumentCaptor<RequirementVersion> versionCaptor = ArgumentCaptor.forClass(RequirementVersion.class);
        verify(intakeStore).appendRequirementVersion(versionCaptor.capture());
        assertThat(versionCaptor.getValue().version()).isEqualTo(3);
        assertThat(versionCaptor.getValue().createdBy().type()).isEqualTo(ActorType.HUMAN);

        ArgumentCaptor<RequirementDoc> docCaptor = ArgumentCaptor.forClass(RequirementDoc.class);
        verify(intakeStore).saveRequirement(docCaptor.capture());
        assertThat(docCaptor.getValue().currentVersion()).isEqualTo(3);
        assertThat(docCaptor.getValue().confirmedVersion()).isNull();
        assertThat(docCaptor.getValue().status()).isEqualTo(RequirementStatus.IN_REVIEW);

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().status()).isEqualTo(TicketStatus.CANCELED);
    }

    @Test
    void shouldConfirmOnlyCurrentReviewVersionAndResolveConfirmationTicket() {
        RequirementDoc doc = new RequirementDoc("requirement-workflow-1", "workflow-1", 2, null, RequirementStatus.IN_REVIEW, "当前标题");
        Ticket confirmationTicket = new Ticket(
                "ticket-1",
                "workflow-1",
                TicketType.DECISION,
                TicketBlockingScope.GLOBAL_BLOCKING,
                TicketStatus.OPEN,
                "需求文档待确认",
                new ActorRef(ActorType.AGENT, "requirement-agent"),
                new ActorRef(ActorType.HUMAN, "user-1"),
                "requirement",
                doc.docId(),
                2,
                payload(Map.of("phase", "CONFIRMATION"))
        );

        when(intakeStore.findRequirement(doc.docId())).thenReturn(Optional.of(doc));
        when(intakeStore.listTicketsForWorkflow("workflow-1")).thenReturn(List.of(confirmationTicket));
        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(workflow("workflow-1", WorkflowRunStatus.WAITING_ON_HUMAN)));

        String workflowRunId = requirementStageService.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                doc.docId(),
                2,
                new ActorRef(ActorType.HUMAN, "user-1")
        ));

        assertThat(workflowRunId).isEqualTo("workflow-1");

        ArgumentCaptor<RequirementDoc> docCaptor = ArgumentCaptor.forClass(RequirementDoc.class);
        verify(intakeStore).saveRequirement(docCaptor.capture());
        assertThat(docCaptor.getValue().status()).isEqualTo(RequirementStatus.CONFIRMED);
        assertThat(docCaptor.getValue().confirmedVersion()).isEqualTo(2);

        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().status()).isEqualTo(TicketStatus.RESOLVED);
    }

    private WorkflowRun workflow(String workflowRunId, WorkflowRunStatus status) {
        return new WorkflowRun(
                workflowRunId,
                "builtin-coding-flow",
                "登录需求",
                status,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "user-1")
        );
    }

    private WorkflowRunEvent startEvent(String workflowRunId) {
        return new WorkflowRunEvent(
                "event-1",
                workflowRunId,
                "WORKFLOW_STARTED",
                new ActorRef(ActorType.HUMAN, "user-1"),
                "started",
                payload(Map.of(
                        "requireHumanClarification", false,
                        "architectCanAutoResolveClarification", false,
                        "verifyNeedsRework", false,
                        "requirementSeedTitle", "用户登录",
                        "requirementSeedContent", "支持邮箱密码登录"
                ))
        );
    }

    private WorkflowNodeBinding requirementBinding(String workflowRunId) {
        return new WorkflowNodeBinding(
                "binding-1",
                workflowRunId,
                "requirement",
                WorkflowBindingMode.DEFAULT,
                "requirement-agent",
                false
        );
    }

    private AgentDefinition requirementAgent() {
        return new AgentDefinition(
                "requirement-agent",
                "Requirement Agent",
                "draft requirements",
                "SYSTEM",
                "in-process",
                "deepseek-chat",
                4,
                false,
                false,
                true,
                true
        );
    }

    private JsonPayload payload(Map<String, Object> data) {
        try {
            return new JsonPayload(new ObjectMapper().writeValueAsString(data));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

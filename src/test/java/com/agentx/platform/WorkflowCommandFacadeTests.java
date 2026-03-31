package com.agentx.platform;

import com.agentx.platform.controlplane.application.WorkflowCommandFacade;
import com.agentx.platform.controlplane.application.WorkflowCommandResult;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.model.TicketBlockingScope;
import com.agentx.platform.domain.intake.model.TicketStatus;
import com.agentx.platform.domain.intake.model.TicketType;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.support.TestStackProfiles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowCommandFacadeTests {

    @Test
    void shouldStartWorkflowAndReturnCommandSummary() {
        FixedCodingWorkflowUseCase useCase = mock(FixedCodingWorkflowUseCase.class);
        WorkflowCommandFacade facade = new WorkflowCommandFacade(useCase);
        when(useCase.start(any(StartCodingWorkflowCommand.class))).thenReturn("workflow-1");
        when(useCase.getRuntimeSnapshot("workflow-1")).thenReturn(snapshot(
                "workflow-1",
                WorkflowRunStatus.ACTIVE,
                new RequirementDoc("req-1", "workflow-1", 1, null, RequirementStatus.IN_REVIEW, "学生管理系统"),
                List.of(
                        humanTicket("ticket-1", "workflow-1", TicketStatus.OPEN, TicketBlockingScope.GLOBAL_BLOCKING, null),
                        humanTicket("ticket-2", "workflow-1", TicketStatus.OPEN, TicketBlockingScope.TASK_BLOCKING, "task-1")
                )
        ));

        WorkflowCommandResult result = facade.startWorkflow(
                "学生管理系统",
                "学生管理系统需求",
                "做一个学生管理系统",
                TestStackProfiles.DEFAULT_PROFILE_ID,
                "user-1",
                true
        );

        ArgumentCaptor<StartCodingWorkflowCommand> commandCaptor = ArgumentCaptor.forClass(StartCodingWorkflowCommand.class);
        verify(useCase).start(commandCaptor.capture());
        assertThat(commandCaptor.getValue().createdBy().type()).isEqualTo(ActorType.HUMAN);
        assertThat(commandCaptor.getValue().createdBy().actorId()).isEqualTo("user-1");
        assertThat(commandCaptor.getValue().autoAgentMode()).isTrue();

        assertThat(result.workflowRunId()).isEqualTo("workflow-1");
        assertThat(result.workflowStatus()).isEqualTo(WorkflowRunStatus.ACTIVE);
        assertThat(result.requirementDocId()).isEqualTo("req-1");
        assertThat(result.pendingHumanTickets()).isEqualTo(2);
        assertThat(result.openGlobalBlockers()).isEqualTo(1);
        assertThat(result.openTaskBlockers()).isEqualTo(1);
    }

    @Test
    void shouldAnswerTicketAsHumanActor() {
        FixedCodingWorkflowUseCase useCase = mock(FixedCodingWorkflowUseCase.class);
        WorkflowCommandFacade facade = new WorkflowCommandFacade(useCase);
        when(useCase.answerTicket(any(AnswerTicketCommand.class))).thenReturn(snapshot(
                "workflow-1",
                WorkflowRunStatus.ACTIVE,
                null,
                List.of()
        ));

        facade.answerTicket("ticket-1", "已补充需求", "reviewer-1");

        ArgumentCaptor<AnswerTicketCommand> commandCaptor = ArgumentCaptor.forClass(AnswerTicketCommand.class);
        verify(useCase).answerTicket(commandCaptor.capture());
        assertThat(commandCaptor.getValue().answeredBy().type()).isEqualTo(ActorType.HUMAN);
        assertThat(commandCaptor.getValue().answeredBy().actorId()).isEqualTo("reviewer-1");
    }

    @Test
    void shouldRejectRequirementEditWhenDocDoesNotBelongToWorkflow() {
        FixedCodingWorkflowUseCase useCase = mock(FixedCodingWorkflowUseCase.class);
        WorkflowCommandFacade facade = new WorkflowCommandFacade(useCase);
        when(useCase.getRuntimeSnapshot("workflow-1")).thenReturn(snapshot(
                "workflow-1",
                WorkflowRunStatus.WAITING_ON_HUMAN,
                new RequirementDoc("req-1", "workflow-1", 1, null, RequirementStatus.IN_REVIEW, "学生管理系统"),
                List.of()
        ));

        assertThatThrownBy(() -> facade.editRequirement(
                "workflow-1",
                "req-2",
                "学生管理系统",
                "新的内容",
                "editor-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to workflow");
    }

    private WorkflowRuntimeSnapshot snapshot(
            String workflowRunId,
            WorkflowRunStatus status,
            RequirementDoc requirementDoc,
            List<Ticket> tickets
    ) {
        return new WorkflowRuntimeSnapshot(
                new WorkflowRun(
                        workflowRunId,
                        "builtin-coding-flow",
                        "学生管理系统",
                        status,
                        EntryMode.MANUAL,
                        true,
                        new ActorRef(ActorType.HUMAN, "starter-1")
                ),
                Optional.of(TestStackProfiles.defaultProfileRef()),
                Optional.ofNullable(requirementDoc),
                List.of(),
                tickets,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private Ticket humanTicket(
            String ticketId,
            String workflowRunId,
            TicketStatus status,
            TicketBlockingScope blockingScope,
            String taskId
    ) {
        return new Ticket(
                ticketId,
                workflowRunId,
                TicketType.CLARIFICATION,
                blockingScope,
                status,
                "需要补充信息",
                new ActorRef(ActorType.AGENT, "agent-1"),
                new ActorRef(ActorType.HUMAN, "human-1"),
                "requirement",
                null,
                null,
                taskId,
                JsonPayload.emptyObject()
        );
    }
}

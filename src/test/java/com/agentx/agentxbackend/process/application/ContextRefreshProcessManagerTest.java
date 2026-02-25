package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextRefreshProcessManagerTest {

    @Mock
    private ContextCompileUseCase contextCompileUseCase;
    @Mock
    private TicketQueryUseCase ticketQueryUseCase;
    @Mock
    private TicketCommandUseCase ticketCommandUseCase;
    @Mock
    private RunInternalUseCase runInternalUseCase;

    @Test
    void handleRequirementConfirmedShouldRefreshBySession() {
        ContextRefreshProcessManager manager = new ContextRefreshProcessManager(
            contextCompileUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            runInternalUseCase,
            new ObjectMapper(),
            true,
            256,
            256
        );
        RequirementConfirmedEvent event = new RequirementConfirmedEvent("SES-1", "REQ-1", 2, 1);
        when(contextCompileUseCase.refreshTaskContextsBySession("SES-1", "REQUIREMENT_CONFIRMED", 256))
            .thenReturn(3);

        manager.handleRequirementConfirmed(event);

        verify(contextCompileUseCase).refreshTaskContextsBySession("SES-1", "REQUIREMENT_CONFIRMED", 256);
    }

    @Test
    void handleTicketEventShouldRefreshOnUserResponded() {
        ContextRefreshProcessManager manager = new ContextRefreshProcessManager(
            contextCompileUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            runInternalUseCase,
            new ObjectMapper(),
            true,
            256,
            256
        );
        TicketEventAppendedEvent event = new TicketEventAppendedEvent(
            "TCK-1",
            "SES-1",
            TicketType.ARCH_REVIEW,
            "architect_agent",
            TicketEventType.USER_RESPONDED,
            TicketStatus.IN_PROGRESS
        );
        when(contextCompileUseCase.refreshTaskContextsByTicket("TCK-1", "TICKET_DONE", 256))
            .thenReturn(2);
        when(ticketQueryUseCase.findById("TCK-1"))
            .thenReturn(new Ticket(
                "TCK-1",
                "SES-1",
                TicketType.ARCH_REVIEW,
                TicketStatus.IN_PROGRESS,
                "run need input",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{\"kind\":\"run_need_input\",\"run_id\":\"RUN-1\",\"task_id\":\"TASK-1\"}",
                "architect-agent-auto",
                Instant.parse("2026-02-24T00:10:00Z"),
                Instant.parse("2026-02-24T00:00:00Z"),
                Instant.parse("2026-02-24T00:00:00Z")
            ));
        when(runInternalUseCase.failWaitingRunForUserResponse(
            "RUN-1",
            "User responded on ticket TCK-1; run is superseded for refreshed context dispatch."
        )).thenReturn(new TaskRun(
            "RUN-1",
            "TASK-1",
            "WRK-1",
            RunStatus.FAILED,
            RunKind.IMPL,
            "CTXS-1",
            Instant.parse("2026-02-24T00:10:00Z"),
            Instant.parse("2026-02-24T00:09:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-24T00:10:00Z"),
            "file:.agentx/context/task-skills/TASK-1.md",
            "[\"TP-JAVA-21\"]",
            "abc123",
            "run/RUN-1",
            "worktrees/TASK-1/RUN-1",
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-24T00:10:00Z")
        ));

        manager.handleTicketEvent(event);

        verify(contextCompileUseCase).refreshTaskContextsByTicket("TCK-1", "TICKET_DONE", 256);
        verify(runInternalUseCase).failWaitingRunForUserResponse(
            "RUN-1",
            "User responded on ticket TCK-1; run is superseded for refreshed context dispatch."
        );
        verify(ticketCommandUseCase).appendEvent(
            org.mockito.ArgumentMatchers.eq("TCK-1"),
            org.mockito.ArgumentMatchers.eq("architect_agent"),
            org.mockito.ArgumentMatchers.eq("STATUS_CHANGED"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.contains("\"to_status\":\"DONE\"")
        );
    }

    @Test
    void handleTicketEventShouldSkipNonArchitectTicket() {
        ContextRefreshProcessManager manager = new ContextRefreshProcessManager(
            contextCompileUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            runInternalUseCase,
            new ObjectMapper(),
            true,
            256,
            256
        );
        TicketEventAppendedEvent event = new TicketEventAppendedEvent(
            "TCK-2",
            "SES-1",
            TicketType.HANDOFF,
            "requirement_agent",
            TicketEventType.USER_RESPONDED,
            TicketStatus.IN_PROGRESS
        );

        manager.handleTicketEvent(event);

        verify(contextCompileUseCase, never()).refreshTaskContextsByTicket("TCK-2", "TICKET_DONE", 256);
    }

    @Test
    void handleTicketEventShouldSkipIrrelevantEventType() {
        ContextRefreshProcessManager manager = new ContextRefreshProcessManager(
            contextCompileUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            runInternalUseCase,
            new ObjectMapper(),
            true,
            256,
            256
        );
        TicketEventAppendedEvent event = new TicketEventAppendedEvent(
            "TCK-3",
            "SES-1",
            TicketType.ARCH_REVIEW,
            "architect_agent",
            TicketEventType.COMMENT,
            TicketStatus.IN_PROGRESS
        );

        manager.handleTicketEvent(event);

        verify(contextCompileUseCase, never()).refreshTaskContextsByTicket("TCK-3", "TICKET_DONE", 256);
    }

    @Test
    void handleRunFinishedShouldRefreshByTask() {
        ContextRefreshProcessManager manager = new ContextRefreshProcessManager(
            contextCompileUseCase,
            ticketQueryUseCase,
            ticketCommandUseCase,
            runInternalUseCase,
            new ObjectMapper(),
            true,
            256,
            256
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-1",
            "TASK-1",
            new RunFinishedPayload("SUCCEEDED", "ok", "abc123", "[]")
        );
        when(contextCompileUseCase.refreshTaskContextByTask("TASK-1", "RUN_FINISHED")).thenReturn(true);

        manager.handleRunFinished(event);

        verify(contextCompileUseCase).refreshTaskContextByTask("TASK-1", "RUN_FINISHED");
    }
}

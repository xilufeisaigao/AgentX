package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.planning.application.port.in.WaitingTaskQueryUseCase;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementCurrentDoc;
import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocQueryUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunNeedsInputProcessManagerTest {

    @Mock
    private TicketCommandUseCase ticketCommandUseCase;
    @Mock
    private TicketQueryUseCase ticketQueryUseCase;
    @Mock
    private WaitingTaskQueryUseCase waitingTaskQueryUseCase;
    @Mock
    private RequirementDocQueryUseCase requirementDocQueryUseCase;

    @Test
    void handleDecisionShouldCreateDecisionTicketClaimAndAppendDecisionRequestedEvent() {
        RunNeedsInputProcessManager manager = new RunNeedsInputProcessManager(
            ticketCommandUseCase,
            ticketQueryUseCase,
            waitingTaskQueryUseCase,
            requirementDocQueryUseCase,
            new ObjectMapper(),
            "architect-agent-auto",
            300
        );
        RunNeedsDecisionEvent event = new RunNeedsDecisionEvent(
            "RUN-1",
            "TASK-1",
            "Need user decision on API behavior",
            "{\"options\":[\"A\",\"B\"]}"
        );
        when(waitingTaskQueryUseCase.findSessionIdByTaskId("TASK-1")).thenReturn(Optional.of("SES-1"));
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", "DECISION"))
            .thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1"))
            .thenReturn(Optional.of(
                new RequirementCurrentDoc(
                    "REQ-1",
                    5,
                    4,
                    "CONFIRMED",
                    "title",
                    "content",
                    Instant.parse("2026-02-23T00:00:00Z")
                )
            ));
        when(ticketCommandUseCase.createTicket(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new Ticket(
                "TCK-1",
                "SES-1",
                TicketType.DECISION,
                TicketStatus.OPEN,
                "Decision",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                null,
                null,
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));
        when(ticketCommandUseCase.claimTicket("TCK-1", "architect-agent-auto", 300))
            .thenReturn(new Ticket(
                "TCK-1",
                "SES-1",
                TicketType.DECISION,
                TicketStatus.IN_PROGRESS,
                "Decision",
                "architect_agent",
                "architect_agent",
                "REQ-1",
                4,
                "{}",
                "architect-agent-auto",
                Instant.parse("2026-02-23T00:05:00Z"),
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));

        manager.handle(event);

        ArgumentCaptor<TicketType> typeCaptor = ArgumentCaptor.forClass(TicketType.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> docVerCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase).createTicket(
            any(),
            typeCaptor.capture(),
            any(),
            any(),
            any(),
            docIdCaptor.capture(),
            docVerCaptor.capture(),
            payloadCaptor.capture()
        );
        assertTrue(typeCaptor.getValue() == TicketType.DECISION);
        assertTrue("REQ-1".equals(docIdCaptor.getValue()));
        assertTrue(docVerCaptor.getValue() == 4);
        assertTrue(payloadCaptor.getValue().contains("\"run_id\":\"RUN-1\""));
        assertTrue(payloadCaptor.getValue().contains("\"task_id\":\"TASK-1\""));
        verify(ticketCommandUseCase).claimTicket("TCK-1", "architect-agent-auto", 300);

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, times(2)).appendEvent(
            eq("TCK-1"),
            eq("architect_agent"),
            eventTypeCaptor.capture(),
            bodyCaptor.capture(),
            dataCaptor.capture()
        );
        assertTrue(eventTypeCaptor.getAllValues().contains(TicketEventType.COMMENT.name()));
        assertTrue(eventTypeCaptor.getAllValues().contains(TicketEventType.DECISION_REQUESTED.name()));
        assertTrue(bodyCaptor.getAllValues().contains("Need user decision on API behavior"));
        assertTrue(dataCaptor.getAllValues().stream().anyMatch(v -> v.contains("\"request_kind\":\"DECISION\"")));
        assertTrue(dataCaptor.getAllValues().stream().anyMatch(v -> v.contains("\"options\"")));
    }

    @Test
    void handleClarificationShouldCreateClarificationTicketAndForwardToUser() {
        RunNeedsInputProcessManager manager = new RunNeedsInputProcessManager(
            ticketCommandUseCase,
            ticketQueryUseCase,
            waitingTaskQueryUseCase,
            requirementDocQueryUseCase,
            new ObjectMapper(),
            "architect-agent-auto",
            300
        );
        RunNeedsClarificationEvent event = new RunNeedsClarificationEvent(
            "RUN-2",
            "TASK-2",
            "Missing requirement details",
            null
        );
        when(waitingTaskQueryUseCase.findSessionIdByTaskId("TASK-2")).thenReturn(Optional.of("SES-2"));
        when(ticketQueryUseCase.listBySession("SES-2", null, "architect_agent", "CLARIFICATION"))
            .thenReturn(List.of());
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-2")).thenReturn(Optional.empty());
        when(ticketCommandUseCase.createTicket(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new Ticket(
                "TCK-2",
                "SES-2",
                TicketType.CLARIFICATION,
                TicketStatus.OPEN,
                "Clarification",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                null,
                null,
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));
        when(ticketCommandUseCase.claimTicket("TCK-2", "architect-agent-auto", 300))
            .thenReturn(new Ticket(
                "TCK-2",
                "SES-2",
                TicketType.CLARIFICATION,
                TicketStatus.IN_PROGRESS,
                "Clarification",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                "architect-agent-auto",
                Instant.parse("2026-02-23T00:05:00Z"),
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));

        manager.handle(event);

        verify(ticketCommandUseCase).createTicket(
            any(),
            org.mockito.ArgumentMatchers.eq(TicketType.CLARIFICATION),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        );
        verify(ticketCommandUseCase).claimTicket("TCK-2", "architect-agent-auto", 300);
        ArgumentCaptor<String> dataCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketCommandUseCase, times(2)).appendEvent(
            eq("TCK-2"),
            eq("architect_agent"),
            any(),
            any(),
            dataCaptor.capture()
        );
        assertTrue(dataCaptor.getAllValues().stream().anyMatch(v -> v.contains("\"request_kind\":\"CLARIFICATION\"")));
    }

    @Test
    void handleShouldThrowWhenSessionCannotBeResolved() {
        RunNeedsInputProcessManager manager = new RunNeedsInputProcessManager(
            ticketCommandUseCase,
            ticketQueryUseCase,
            waitingTaskQueryUseCase,
            requirementDocQueryUseCase,
            new ObjectMapper(),
            "architect-agent-auto",
            300
        );
        when(waitingTaskQueryUseCase.findSessionIdByTaskId("TASK-3")).thenReturn(Optional.empty());

        assertThrows(
            RuntimeException.class,
            () -> manager.handle(new RunNeedsDecisionEvent("RUN-3", "TASK-3", "Need choice", null))
        );
    }

    @Test
    void handleDecisionShouldReuseExistingTicketForSameRunAndTask() {
        RunNeedsInputProcessManager manager = new RunNeedsInputProcessManager(
            ticketCommandUseCase,
            ticketQueryUseCase,
            waitingTaskQueryUseCase,
            requirementDocQueryUseCase,
            new ObjectMapper(),
            "architect-agent-auto",
            300
        );
        Ticket existing = new Ticket(
            "TCK-EXISTING",
            "SES-1",
            TicketType.DECISION,
            TicketStatus.WAITING_USER,
            "Existing",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{\"kind\":\"run_need_input\",\"run_id\":\"RUN-EXIST\",\"task_id\":\"TASK-EXIST\",\"ticket_type\":\"DECISION\"}",
            "architect-agent-auto",
            Instant.parse("2026-02-23T00:05:00Z"),
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-23T00:00:00Z")
        );
        when(waitingTaskQueryUseCase.findSessionIdByTaskId("TASK-EXIST")).thenReturn(Optional.of("SES-1"));
        when(ticketQueryUseCase.listBySession("SES-1", null, "architect_agent", "DECISION"))
            .thenReturn(List.of(existing));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-1")).thenReturn(Optional.empty());

        manager.handle(new RunNeedsDecisionEvent(
            "RUN-EXIST",
            "TASK-EXIST",
            "Need user choice",
            "{\"options\":[\"A\",\"B\"]}"
        ));

        verify(ticketCommandUseCase, never()).createTicket(any(), any(), any(), any(), any(), any(), any(), any());
        verify(ticketCommandUseCase).appendEvent(
            eq("TCK-EXISTING"),
            eq("architect_agent"),
            eq("COMMENT"),
            any(),
            any()
        );
    }

    @Test
    void handleDecisionShouldSupersedeOldRunNeedInputTicket() {
        RunNeedsInputProcessManager manager = new RunNeedsInputProcessManager(
            ticketCommandUseCase,
            ticketQueryUseCase,
            waitingTaskQueryUseCase,
            requirementDocQueryUseCase,
            new ObjectMapper(),
            "architect-agent-auto",
            300
        );
        Ticket stale = new Ticket(
            "TCK-STALE",
            "SES-9",
            TicketType.DECISION,
            TicketStatus.WAITING_USER,
            "stale",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{\"kind\":\"run_need_input\",\"run_id\":\"RUN-OLD\",\"task_id\":\"TASK-9\",\"ticket_type\":\"DECISION\"}",
            "architect-agent-auto",
            Instant.parse("2026-02-23T00:05:00Z"),
            Instant.parse("2026-02-23T00:00:00Z"),
            Instant.parse("2026-02-23T00:00:00Z")
        );
        when(waitingTaskQueryUseCase.findSessionIdByTaskId("TASK-9")).thenReturn(Optional.of("SES-9"));
        when(ticketQueryUseCase.listBySession("SES-9", null, "architect_agent", "DECISION"))
            .thenReturn(List.of(stale));
        when(requirementDocQueryUseCase.findCurrentBySessionId("SES-9")).thenReturn(Optional.empty());
        when(ticketCommandUseCase.createTicket(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new Ticket(
                "TCK-NEW",
                "SES-9",
                TicketType.DECISION,
                TicketStatus.OPEN,
                "new",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                null,
                null,
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));
        when(ticketCommandUseCase.claimTicket("TCK-NEW", "architect-agent-auto", 300))
            .thenReturn(new Ticket(
                "TCK-NEW",
                "SES-9",
                TicketType.DECISION,
                TicketStatus.IN_PROGRESS,
                "new",
                "architect_agent",
                "architect_agent",
                null,
                null,
                "{}",
                "architect-agent-auto",
                Instant.parse("2026-02-23T00:05:00Z"),
                Instant.parse("2026-02-23T00:00:00Z"),
                Instant.parse("2026-02-23T00:00:00Z")
            ));

        manager.handle(new RunNeedsDecisionEvent(
            "RUN-NEW",
            "TASK-9",
            "Need updated decision",
            null
        ));

        verify(ticketCommandUseCase).appendEvent(
            eq("TCK-STALE"),
            eq("architect_agent"),
            eq("STATUS_CHANGED"),
            any(),
            org.mockito.ArgumentMatchers.contains("\"to_status\":\"BLOCKED\"")
        );
        verify(ticketCommandUseCase).createTicket(any(), eq(TicketType.DECISION), any(), any(), any(), any(), any(), any());
    }
}

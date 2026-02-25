package com.agentx.agentxbackend.ticket.application;

import com.agentx.agentxbackend.ticket.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.ticket.application.port.out.TicketEventRepository;
import com.agentx.agentxbackend.ticket.application.port.out.TicketRepository;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketCommandServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketEventRepository ticketEventRepository;
    @Mock
    private DomainEventPublisher domainEventPublisher;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks
    private TicketCommandService service;

    @Test
    void createTicketShouldPersistOpenTicket() {
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket created = service.createTicket(
            "SES-1",
            TicketType.ARCH_REVIEW,
            "review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-88",
            2,
            "{\"k\":\"v\"}"
        );

        assertTrue(created.ticketId().startsWith("TCK-"));
        assertEquals("SES-1", created.sessionId());
        assertEquals(TicketType.ARCH_REVIEW, created.type());
        assertEquals(TicketStatus.OPEN, created.status());
        assertEquals("requirement_agent", created.createdByRole());
        assertEquals("architect_agent", created.assigneeRole());
        assertEquals("REQ-88", created.requirementDocId());
        assertEquals(2, created.requirementDocVer());
        verify(ticketRepository).save(any());
    }

    @Test
    void createTicketShouldRejectInvalidRole() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createTicket(
                "SES-1",
                TicketType.DECISION,
                "title",
                "system",
                "architect_agent",
                null,
                null,
                "{}"
            )
        );
    }

    @Test
    void createTicketShouldRejectInvalidAssigneeRole() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createTicket(
                "SES-1",
                TicketType.HANDOFF,
                "title",
                "architect_agent",
                "user",
                null,
                null,
                "{}"
            )
        );
    }

    @Test
    void createTicketShouldRejectPartialRequirementReference() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createTicket(
                "SES-1",
                TicketType.HANDOFF,
                "title",
                "architect_agent",
                "architect_agent",
                "REQ-1",
                null,
                "{}"
            )
        );
    }

    @Test
    void claimTicketShouldMoveStatusToInProgressAndSetLease() {
        Ticket claimedTicket = new Ticket(
            "TCK-1",
            "SES-1",
            TicketType.CLARIFICATION,
            TicketStatus.IN_PROGRESS,
            "Need detail",
            "user",
            "requirement_agent",
            null,
            null,
            "{}",
            "agent-1",
            Instant.now().plusSeconds(120),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.now()
        );
        when(ticketRepository.claimIfOpen(any(), any(), any(), any())).thenReturn(true);
        when(ticketRepository.findById("TCK-1")).thenReturn(Optional.of(claimedTicket));

        Ticket claimed = service.claimTicket("TCK-1", "agent-1", 120);

        assertEquals(TicketStatus.IN_PROGRESS, claimed.status());
        assertEquals("agent-1", claimed.claimedBy());
        assertTrue(claimed.leaseUntil().isAfter(Instant.now()));
        verify(ticketRepository).claimIfOpen(any(), any(), any(), any());
    }

    @Test
    void claimTicketShouldFailForTerminalTicket() {
        Ticket doneTicket = new Ticket(
            "TCK-D",
            "SES-1",
            TicketType.ARCH_REVIEW,
            TicketStatus.DONE,
            "done",
            "requirement_agent",
            "architect_agent",
            null,
            null,
            "{}",
            null,
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.claimIfOpen(any(), any(), any(), any())).thenReturn(false);
        when(ticketRepository.findById("TCK-D")).thenReturn(Optional.of(doneTicket));
        assertThrows(IllegalStateException.class, () -> service.claimTicket("TCK-D", "agent", 60));
    }

    @Test
    void claimTicketShouldFailWhenTicketIsNotOpen() {
        Ticket waitingTicket = new Ticket(
            "TCK-W",
            "SES-1",
            TicketType.ARCH_REVIEW,
            TicketStatus.WAITING_USER,
            "waiting user",
            "requirement_agent",
            "architect_agent",
            null,
            null,
            "{}",
            "architect-agent-1",
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.claimIfOpen(any(), any(), any(), any())).thenReturn(false);
        when(ticketRepository.findById("TCK-W")).thenReturn(Optional.of(waitingTicket));
        assertThrows(IllegalStateException.class, () -> service.claimTicket("TCK-W", "agent", 60));
    }

    @Test
    void claimTicketShouldFailWhenTicketNotFound() {
        when(ticketRepository.claimIfOpen(any(), any(), any(), any())).thenReturn(false);
        when(ticketRepository.findById("TCK-X")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.claimTicket("TCK-X", "agent", 60));
    }

    @Test
    void tryMovePlanningLeaseShouldReturnUpdatedTicketWhenCasMatched() {
        Ticket movedTicket = new Ticket(
            "TCK-PLN-1",
            "SES-1",
            TicketType.ARCH_REVIEW,
            TicketStatus.IN_PROGRESS,
            "planning",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            1,
            "{}",
            "architect-agent-auto#planning#token",
            Instant.now().plusSeconds(300),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.now()
        );
        when(ticketRepository.movePlanningLeaseIfInProgressClaimed(any(), any(), any(), any(), any()))
            .thenReturn(true);
        when(ticketRepository.findById("TCK-PLN-1")).thenReturn(Optional.of(movedTicket));

        Optional<Ticket> moved = service.tryMovePlanningLease(
            "TCK-PLN-1",
            "architect-agent-auto",
            "architect-agent-auto#planning#token",
            300
        );

        assertTrue(moved.isPresent());
        assertEquals("architect-agent-auto#planning#token", moved.get().claimedBy());
        verify(ticketRepository).movePlanningLeaseIfInProgressClaimed(any(), any(), any(), any(), any());
    }

    @Test
    void tryMovePlanningLeaseShouldReturnEmptyWhenCasMissed() {
        when(ticketRepository.movePlanningLeaseIfInProgressClaimed(any(), any(), any(), any(), any()))
            .thenReturn(false);

        Optional<Ticket> moved = service.tryMovePlanningLease(
            "TCK-PLN-2",
            "architect-agent-auto",
            "architect-agent-auto#planning#token-2",
            300
        );

        assertTrue(moved.isEmpty());
        verify(ticketRepository).movePlanningLeaseIfInProgressClaimed(any(), any(), any(), any(), any());
    }

    @Test
    void appendEventShouldPersistMappedEventTypeAndMoveToWaitingUser() {
        Ticket inProgress = new Ticket(
            "TCK-2",
            "SES-1",
            TicketType.DECISION,
            TicketStatus.IN_PROGRESS,
            "need user decision",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{}",
            "agent-1",
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-2")).thenReturn(Optional.of(inProgress));
        when(ticketRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TicketEvent event = service.appendEvent(
            "TCK-2",
            "architect_agent",
            "decision_requested",
            "reply",
            "{\"answer\":1}"
        );

        assertEquals("TCK-2", event.ticketId());
        assertEquals("architect_agent", event.actorRole());
        assertEquals(TicketEventType.DECISION_REQUESTED, event.eventType());
        assertEquals("reply", event.body());
        assertEquals("{\"answer\":1}", event.dataJson());
        verify(ticketEventRepository).save(any());
        verify(ticketRepository).update(any());
        verify(domainEventPublisher).publish(any());
    }

    @Test
    void appendEventShouldMoveToInProgressWhenUserResponded() {
        Ticket waiting = new Ticket(
            "TCK-3",
            "SES-1",
            TicketType.DECISION,
            TicketStatus.WAITING_USER,
            "waiting user",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{}",
            "agent-1",
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-3")).thenReturn(Optional.of(waiting));
        when(ticketEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TicketEvent event = service.appendEvent(
            "TCK-3",
            "user",
            "user_responded",
            "picked option B",
            "{\"option\":\"B\"}"
        );

        assertEquals(TicketEventType.USER_RESPONDED, event.eventType());
        verify(ticketRepository).update(any());
        verify(domainEventPublisher).publish(any());
    }

    @Test
    void appendEventShouldMoveToDoneWhenStatusChangedEventProvided() {
        Ticket inProgress = new Ticket(
            "TCK-4",
            "SES-1",
            TicketType.ARCH_REVIEW,
            TicketStatus.IN_PROGRESS,
            "arch review in progress",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{}",
            "architect-agent-1",
            Instant.parse("2026-01-01T00:10:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-4")).thenReturn(Optional.of(inProgress));
        when(ticketEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TicketEvent event = service.appendEvent(
            "TCK-4",
            "architect_agent",
            "STATUS_CHANGED",
            "close arch review",
            "{\"to_status\":\"DONE\",\"reason\":\"decision applied\"}"
        );

        assertEquals(TicketEventType.STATUS_CHANGED, event.eventType());
        verify(ticketRepository).update(any());
        verify(domainEventPublisher).publish(any());
    }

    @Test
    void appendEventShouldRejectIllegalTransitionForStatusChanged() {
        Ticket openTicket = new Ticket(
            "TCK-5",
            "SES-1",
            TicketType.HANDOFF,
            TicketStatus.OPEN,
            "handoff open",
            "requirement_agent",
            "architect_agent",
            null,
            null,
            "{}",
            null,
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-5")).thenReturn(Optional.of(openTicket));

        assertThrows(
            IllegalStateException.class,
            () -> service.appendEvent(
                "TCK-5",
                "architect_agent",
                "STATUS_CHANGED",
                "try close directly",
                "{\"to_status\":\"DONE\"}"
            )
        );
    }

    @Test
    void appendEventShouldRejectStatusChangedWithoutToStatus() {
        Ticket inProgress = new Ticket(
            "TCK-6",
            "SES-1",
            TicketType.ARCH_REVIEW,
            TicketStatus.IN_PROGRESS,
            "arch review",
            "requirement_agent",
            "architect_agent",
            null,
            null,
            "{}",
            null,
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-6")).thenReturn(Optional.of(inProgress));

        assertThrows(
            IllegalArgumentException.class,
            () -> service.appendEvent(
                "TCK-6",
                "architect_agent",
                "STATUS_CHANGED",
                "missing to_status",
                "{\"reason\":\"bad payload\"}"
            )
        );
    }

    @Test
    void appendEventShouldRejectInvalidEventType() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.appendEvent("TCK-1", "user", "invalid_type", "x", null)
        );
    }

    @Test
    void appendEventShouldRejectWhenTicketMissing() {
        when(ticketRepository.findById("TCK-M")).thenReturn(Optional.empty());
        assertThrows(
            NoSuchElementException.class,
            () -> service.appendEvent("TCK-M", "user", "COMMENT", "x", null)
        );
    }
}

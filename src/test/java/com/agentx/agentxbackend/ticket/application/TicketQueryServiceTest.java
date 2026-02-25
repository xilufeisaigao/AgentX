package com.agentx.agentxbackend.ticket.application;

import com.agentx.agentxbackend.ticket.application.port.out.TicketRepository;
import com.agentx.agentxbackend.ticket.application.port.out.TicketEventRepository;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketQueryServiceTest {

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketEventRepository ticketEventRepository;
    @InjectMocks
    private TicketQueryService service;

    @Test
    void listBySessionShouldNormalizeFilters() {
        when(ticketRepository.findBySessionAndFilters(
            "SES-1",
            "WAITING_USER",
            "architect_agent",
            "ARCH_REVIEW"
        ))
            .thenReturn(List.of());

        List<Ticket> result = service.listBySession("SES-1", "waiting_user", "Architect_Agent", "arch_review");

        assertEquals(0, result.size());
        verify(ticketRepository).findBySessionAndFilters(
            "SES-1",
            "WAITING_USER",
            "architect_agent",
            "ARCH_REVIEW"
        );
    }

    @Test
    void listBySessionShouldAllowNullFilters() {
        when(ticketRepository.findBySessionAndFilters("SES-1", null, null, null)).thenReturn(List.of());

        List<Ticket> result = service.listBySession("SES-1", null, null, null);

        assertEquals(0, result.size());
        verify(ticketRepository).findBySessionAndFilters("SES-1", null, null, null);
    }

    @Test
    void listBySessionShouldRejectBlankSessionId() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.listBySession(" ", "WAITING_USER", "architect_agent", "ARCH_REVIEW")
        );
    }

    @Test
    void listBySessionShouldRejectInvalidStatus() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.listBySession("SES-1", "UNKNOWN", "architect_agent", "ARCH_REVIEW")
        );
    }

    @Test
    void listBySessionShouldRejectInvalidAssigneeRole() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.listBySession("SES-1", "OPEN", "user", "ARCH_REVIEW")
        );
    }

    @Test
    void listBySessionShouldRejectInvalidType() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.listBySession("SES-1", "OPEN", "architect_agent", "unknown")
        );
    }

    @Test
    void listEventsShouldReturnTicketEventRows() {
        TicketEvent event = new TicketEvent(
            "TEV-1",
            "TCK-1",
            TicketEventType.COMMENT,
            "architect_agent",
            "analysis",
            "{\"k\":\"v\"}",
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(ticketEventRepository.findByTicketId("TCK-1")).thenReturn(List.of(event));

        List<TicketEvent> events = service.listEvents("TCK-1");

        assertEquals(1, events.size());
        assertEquals("TEV-1", events.get(0).eventId());
        verify(ticketEventRepository).findByTicketId("TCK-1");
    }

    @Test
    void listEventsShouldRejectBlankTicketId() {
        assertThrows(IllegalArgumentException.class, () -> service.listEvents(" "));
    }

    @Test
    void findByIdShouldReturnTicket() {
        Ticket ticket = new Ticket(
            "TCK-1",
            "SES-1",
            com.agentx.agentxbackend.ticket.domain.model.TicketType.DECISION,
            com.agentx.agentxbackend.ticket.domain.model.TicketStatus.IN_PROGRESS,
            "title",
            "architect_agent",
            "architect_agent",
            null,
            null,
            "{}",
            "agent",
            Instant.parse("2026-02-24T00:05:00Z"),
            Instant.parse("2026-02-24T00:00:00Z"),
            Instant.parse("2026-02-24T00:00:00Z")
        );
        when(ticketRepository.findById("TCK-1")).thenReturn(Optional.of(ticket));

        Ticket found = service.findById("TCK-1");

        assertEquals("TCK-1", found.ticketId());
        verify(ticketRepository).findById("TCK-1");
    }

    @Test
    void findByIdShouldRejectBlankTicketId() {
        assertThrows(IllegalArgumentException.class, () -> service.findById(" "));
    }

    @Test
    void findByIdShouldThrowWhenMissing() {
        when(ticketRepository.findById("TCK-MISSING")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.findById("TCK-MISSING"));
    }
}

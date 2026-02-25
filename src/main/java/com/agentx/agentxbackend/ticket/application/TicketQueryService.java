package com.agentx.agentxbackend.ticket.application;

import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.application.port.out.TicketEventRepository;
import com.agentx.agentxbackend.ticket.application.port.out.TicketRepository;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

@Service
public class TicketQueryService implements TicketQueryUseCase {

    private final TicketRepository ticketRepository;
    private final TicketEventRepository ticketEventRepository;

    public TicketQueryService(
        TicketRepository ticketRepository,
        TicketEventRepository ticketEventRepository
    ) {
        this.ticketRepository = ticketRepository;
        this.ticketEventRepository = ticketEventRepository;
    }

    @Override
    public List<Ticket> listBySession(String sessionId, String status, String assigneeRole, String type) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }

        String normalizedStatus = normalizeStatus(status);
        String normalizedAssigneeRole = normalizeAssigneeRole(assigneeRole);
        String normalizedType = normalizeType(type);
        return ticketRepository.findBySessionAndFilters(
            sessionId,
            normalizedStatus,
            normalizedAssigneeRole,
            normalizedType
        );
    }

    @Override
    public Ticket findById(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        return ticketRepository.findById(ticketId)
            .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + ticketId));
    }

    @Override
    public List<TicketEvent> listEvents(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        return ticketEventRepository.findByTicketId(ticketId);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return TicketStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)).name();
    }

    private static String normalizeAssigneeRole(String assigneeRole) {
        if (assigneeRole == null || assigneeRole.isBlank()) {
            return null;
        }
        String normalized = assigneeRole.trim().toLowerCase(Locale.ROOT);
        if (!"requirement_agent".equals(normalized) && !"architect_agent".equals(normalized)) {
            throw new IllegalArgumentException("assigneeRole has unsupported role: " + assigneeRole);
        }
        return normalized;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return TicketType.valueOf(type.trim().toUpperCase(Locale.ROOT)).name();
    }
}

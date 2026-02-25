package com.agentx.agentxbackend.ticket.application.port.in;

import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;

import java.util.Optional;

public interface TicketCommandUseCase {

    Ticket createTicket(
        String sessionId,
        TicketType type,
        String title,
        String createdByRole,
        String assigneeRole,
        String requirementDocId,
        Integer requirementDocVer,
        String payloadJson
    );

    Ticket claimTicket(String ticketId, String claimedBy, int leaseSeconds);

    Optional<Ticket> tryMovePlanningLease(
        String ticketId,
        String expectedClaimedBy,
        String nextClaimedBy,
        int leaseSeconds
    );

    TicketEvent appendEvent(String ticketId, String actorRole, String eventType, String body, String dataJson);
}

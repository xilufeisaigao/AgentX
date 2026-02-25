package com.agentx.agentxbackend.ticket.application.port.out;

import com.agentx.agentxbackend.ticket.domain.model.Ticket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketRepository {

    Ticket save(Ticket ticket);

    Optional<Ticket> findById(String ticketId);

    Ticket update(Ticket ticket);

    boolean claimIfOpen(String ticketId, String claimedBy, Instant leaseUntil, Instant updatedAt);

    boolean movePlanningLeaseIfInProgressClaimed(
        String ticketId,
        String expectedClaimedBy,
        String nextClaimedBy,
        Instant leaseUntil,
        Instant updatedAt
    );

    List<Ticket> findBySessionAndFilters(
        String sessionId,
        String status,
        String assigneeRole,
        String type
    );
}

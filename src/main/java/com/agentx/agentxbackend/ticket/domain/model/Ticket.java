package com.agentx.agentxbackend.ticket.domain.model;

import java.time.Instant;

public record Ticket(
    String ticketId,
    String sessionId,
    TicketType type,
    TicketStatus status,
    String title,
    String createdByRole,
    String assigneeRole,
    String requirementDocId,
    Integer requirementDocVer,
    String payloadJson,
    String claimedBy,
    Instant leaseUntil,
    Instant createdAt,
    Instant updatedAt
) {
}

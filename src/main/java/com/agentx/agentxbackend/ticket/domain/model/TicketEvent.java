package com.agentx.agentxbackend.ticket.domain.model;

import java.time.Instant;

public record TicketEvent(
    String eventId,
    String ticketId,
    TicketEventType eventType,
    String actorRole,
    String body,
    String dataJson,
    Instant createdAt
) {
}

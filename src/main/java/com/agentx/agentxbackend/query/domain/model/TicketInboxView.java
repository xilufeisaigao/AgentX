package com.agentx.agentxbackend.query.domain.model;

public record TicketInboxView(
    String ticketId,
    String sessionId,
    String type,
    String status,
    String title
) {
}

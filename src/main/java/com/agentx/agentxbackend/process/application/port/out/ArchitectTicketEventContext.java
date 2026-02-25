package com.agentx.agentxbackend.process.application.port.out;

public record ArchitectTicketEventContext(
    String eventType,
    String actorRole,
    String body,
    String dataJson,
    String createdAt
) {
}

package com.agentx.agentxbackend.session.domain.event;

public record SessionCreatedEvent(
    String sessionId,
    String title
) {
}

package com.agentx.agentxbackend.session.domain.model;

import java.time.Instant;

public record Session(
    String sessionId,
    String title,
    SessionStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}

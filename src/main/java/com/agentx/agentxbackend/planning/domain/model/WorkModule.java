package com.agentx.agentxbackend.planning.domain.model;

import java.time.Instant;

public record WorkModule(
    String moduleId,
    String sessionId,
    String name,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
}

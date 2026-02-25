package com.agentx.agentxbackend.workforce.domain.model;

import java.time.Instant;

public record Toolpack(
    String toolpackId,
    String name,
    String version,
    String kind,
    String description,
    Instant createdAt
) {
}

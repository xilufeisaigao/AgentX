package com.agentx.agentxbackend.workforce.domain.model;

import java.time.Instant;

public record Worker(
    String workerId,
    WorkerStatus status,
    Instant createdAt,
    Instant updatedAt
) {
}

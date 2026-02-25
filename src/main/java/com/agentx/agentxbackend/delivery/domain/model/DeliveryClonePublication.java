package com.agentx.agentxbackend.delivery.domain.model;

import java.time.Instant;

public record DeliveryClonePublication(
    String sessionId,
    String repositoryName,
    String cloneUrl,
    Instant publishedAt,
    Instant expiresAt
) {
}

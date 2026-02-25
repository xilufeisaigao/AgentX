package com.agentx.agentxbackend.execution.domain.model;

import java.time.Instant;

public record Lease(
    Instant leaseUntil,
    Instant lastHeartbeatAt
) {
}

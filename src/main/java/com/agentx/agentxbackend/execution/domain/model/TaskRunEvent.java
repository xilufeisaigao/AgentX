package com.agentx.agentxbackend.execution.domain.model;

import java.time.Instant;

public record TaskRunEvent(
    String eventId,
    String runId,
    RunEventType eventType,
    String body,
    String dataJson,
    Instant createdAt
) {
}

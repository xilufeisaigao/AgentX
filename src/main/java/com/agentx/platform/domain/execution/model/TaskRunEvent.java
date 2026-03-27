package com.agentx.platform.domain.execution.model;

import java.util.Objects;

public record TaskRunEvent(
        String eventId,
        String runId,
        String eventType,
        String body
) {

    public TaskRunEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}

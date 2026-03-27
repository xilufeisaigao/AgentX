package com.agentx.platform.domain.flow.model;

import com.agentx.platform.domain.shared.model.JsonPayload;

import java.util.Objects;

public record WorkflowNodeRunEvent(
        String eventId,
        String nodeRunId,
        String eventType,
        String body,
        JsonPayload dataJson
) {

    public WorkflowNodeRunEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(nodeRunId, "nodeRunId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}

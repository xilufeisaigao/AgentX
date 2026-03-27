package com.agentx.platform.domain.flow.model;

import com.agentx.platform.domain.shared.model.ActorRef;

import java.util.Objects;

public record WorkflowRunEvent(
        String eventId,
        String workflowRunId,
        String eventType,
        ActorRef actor,
        String body
) {

    public WorkflowRunEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }
}

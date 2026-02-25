package com.agentx.agentxbackend.query.domain.model;

public record RunTimelineView(
    String runId,
    String taskId,
    String status,
    String eventType,
    String body
) {
}

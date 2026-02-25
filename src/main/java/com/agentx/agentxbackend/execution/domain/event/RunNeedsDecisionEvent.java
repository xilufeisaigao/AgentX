package com.agentx.agentxbackend.execution.domain.event;

public record RunNeedsDecisionEvent(
    String runId,
    String taskId,
    String body,
    String dataJson
) {
}

package com.agentx.agentxbackend.execution.domain.event;

public record RunNeedsClarificationEvent(
    String runId,
    String taskId,
    String body,
    String dataJson
) {
}

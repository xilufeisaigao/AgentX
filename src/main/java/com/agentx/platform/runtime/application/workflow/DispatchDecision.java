package com.agentx.platform.runtime.application.workflow;

public record DispatchDecision(
        String taskId,
        boolean dispatched,
        String reason,
        String runId
) {
}

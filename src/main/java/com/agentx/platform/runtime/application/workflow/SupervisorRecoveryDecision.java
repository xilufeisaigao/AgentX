package com.agentx.platform.runtime.application.workflow;

public record SupervisorRecoveryDecision(
        String runId,
        String action,
        String reason
) {
}

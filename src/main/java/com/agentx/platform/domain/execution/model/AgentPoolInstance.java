package com.agentx.platform.domain.execution.model;

import java.util.Objects;

public record AgentPoolInstance(
        String agentInstanceId,
        String agentId,
        String runtimeType,
        AgentPoolStatus status,
        String launchMode,
        String currentWorkflowRunId
) {

    public AgentPoolInstance {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(runtimeType, "runtimeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(launchMode, "launchMode must not be null");
    }
}

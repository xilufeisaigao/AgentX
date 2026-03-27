package com.agentx.platform.domain.execution.model;

import com.agentx.platform.domain.shared.model.JsonPayload;

import java.time.LocalDateTime;
import java.util.Objects;

public record AgentPoolInstance(
        String agentInstanceId,
        String agentId,
        String runtimeType,
        AgentPoolStatus status,
        String launchMode,
        String currentWorkflowRunId,
        LocalDateTime leaseUntil,
        LocalDateTime lastHeartbeatAt,
        String endpointRef,
        JsonPayload runtimeMetadataJson
) {

    public AgentPoolInstance {
        Objects.requireNonNull(agentInstanceId, "agentInstanceId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(runtimeType, "runtimeType must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(launchMode, "launchMode must not be null");
    }
}

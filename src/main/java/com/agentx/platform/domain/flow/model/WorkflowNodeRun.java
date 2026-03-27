package com.agentx.platform.domain.flow.model;

import com.agentx.platform.domain.shared.model.JsonPayload;

import java.time.LocalDateTime;
import java.util.Objects;

public record WorkflowNodeRun(
        String nodeRunId,
        String workflowRunId,
        String nodeId,
        String selectedAgentId,
        String agentInstanceId,
        WorkflowNodeRunStatus status,
        JsonPayload inputPayloadJson,
        JsonPayload outputPayloadJson,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {

    public WorkflowNodeRun {
        Objects.requireNonNull(nodeRunId, "nodeRunId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}

package com.agentx.platform.domain.flow.model;

import java.util.Objects;

public record WorkflowNodeRun(
        String nodeRunId,
        String workflowRunId,
        String nodeId,
        String selectedAgentId,
        String agentInstanceId,
        WorkflowNodeRunStatus status
) {

    public WorkflowNodeRun {
        Objects.requireNonNull(nodeRunId, "nodeRunId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}

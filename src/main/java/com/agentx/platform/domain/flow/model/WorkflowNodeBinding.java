package com.agentx.platform.domain.flow.model;

import java.util.Objects;

public record WorkflowNodeBinding(
        String bindingId,
        String workflowRunId,
        String nodeId,
        WorkflowBindingMode bindingMode,
        String selectedAgentId,
        boolean lockedByUser
) {

    public WorkflowNodeBinding {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(bindingMode, "bindingMode must not be null");
        Objects.requireNonNull(selectedAgentId, "selectedAgentId must not be null");
    }
}

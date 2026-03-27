package com.agentx.platform.domain.flow.model;

import java.util.Objects;

public record WorkflowTemplateNode(
        String workflowTemplateId,
        String nodeId,
        String displayName,
        WorkflowNodeKind nodeKind,
        int sequenceNo,
        String defaultAgentId,
        boolean agentBindingConfigurable
) {

    public WorkflowTemplateNode {
        Objects.requireNonNull(workflowTemplateId, "workflowTemplateId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(nodeKind, "nodeKind must not be null");
    }
}

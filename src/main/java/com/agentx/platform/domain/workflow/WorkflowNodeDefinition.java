package com.agentx.platform.domain.workflow;

public record WorkflowNodeDefinition(
    String nodeId,
    String displayName,
    WorkflowNodeKind kind,
    String defaultAgentId,
    boolean agentBindingConfigurable
) {

    public WorkflowNodeDefinition {
        nodeId = requireText(nodeId, "nodeId");
        displayName = requireText(displayName, "displayName");
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (defaultAgentId != null) {
            defaultAgentId = defaultAgentId.trim();
            if (defaultAgentId.isEmpty()) {
                defaultAgentId = null;
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}


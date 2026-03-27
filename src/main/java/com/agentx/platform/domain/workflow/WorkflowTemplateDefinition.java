package com.agentx.platform.domain.workflow;

import java.util.List;
import java.util.Set;

public record WorkflowTemplateDefinition(
    String workflowId,
    String displayName,
    String description,
    WorkflowMutability mutability,
    List<WorkflowNodeDefinition> nodes,
    Set<String> configurableAgentNodeIds,
    Set<String> configurableParameters
) {

    public WorkflowTemplateDefinition {
        workflowId = requireText(workflowId, "workflowId");
        displayName = requireText(displayName, "displayName");
        description = requireText(description, "description");
        if (mutability == null) {
            throw new IllegalArgumentException("mutability must not be null");
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        configurableAgentNodeIds = configurableAgentNodeIds == null ? Set.of() : Set.copyOf(configurableAgentNodeIds);
        configurableParameters = configurableParameters == null ? Set.of() : Set.copyOf(configurableParameters);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}


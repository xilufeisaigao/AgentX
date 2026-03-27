package com.agentx.platform.domain.flow.model;

import java.util.List;
import java.util.Objects;

public record WorkflowTemplate(
        String workflowTemplateId,
        String displayName,
        String description,
        WorkflowMutability mutability,
        String registrationPolicy,
        boolean systemBuiltin,
        boolean enabled,
        String version,
        List<WorkflowTemplateNode> nodes
) {

    public WorkflowTemplate {
        Objects.requireNonNull(workflowTemplateId, "workflowTemplateId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(mutability, "mutability must not be null");
        Objects.requireNonNull(registrationPolicy, "registrationPolicy must not be null");
        Objects.requireNonNull(version, "version must not be null");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
    }
}

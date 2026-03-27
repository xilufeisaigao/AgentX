package com.agentx.platform.domain.planning.model;

import java.util.Objects;

public record WorkModule(
        String moduleId,
        String workflowRunId,
        String name,
        String description
) {

    public WorkModule {
        Objects.requireNonNull(moduleId, "moduleId must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}

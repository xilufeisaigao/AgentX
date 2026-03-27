package com.agentx.platform.domain.planning.model;

import java.util.Objects;

public record TaskDependency(
        String taskId,
        String dependsOnTaskId,
        WorkTaskStatus requiredUpstreamStatus
) {

    public TaskDependency {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        Objects.requireNonNull(requiredUpstreamStatus, "requiredUpstreamStatus must not be null");
    }
}

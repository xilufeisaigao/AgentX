package com.agentx.agentxbackend.planning.domain.model;

import java.time.Instant;

public record WorkTaskDependency(
    String taskId,
    String dependsOnTaskId,
    TaskStatus requiredUpstreamStatus,
    Instant createdAt
) {
}

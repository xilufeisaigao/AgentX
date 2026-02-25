package com.agentx.agentxbackend.planning.domain.model;

import java.time.Instant;

public record WorkTask(
    String taskId,
    String moduleId,
    String title,
    TaskTemplateId taskTemplateId,
    TaskStatus status,
    String requiredToolpacksJson,
    String activeRunId,
    String createdByRole,
    Instant createdAt,
    Instant updatedAt
) {
}

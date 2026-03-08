package com.agentx.agentxbackend.query.domain.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RunTimelineView(
    String sessionId,
    int totalItems,
    List<RunItem> items
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunItem(
        String runId,
        String taskId,
        String taskTitle,
        String moduleId,
        String moduleName,
        String workerId,
        String runKind,
        String runStatus,
        String eventType,
        String eventBody,
        String eventDataJson,
        Instant eventCreatedAt,
        Instant startedAt,
        Instant finishedAt,
        String branchName
    ) {
    }
}

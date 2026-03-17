package com.agentx.agentxbackend.query.domain.model;

import java.time.Instant;
import java.util.List;

public record RunTimelineView(
    String sessionId,
    int totalItems,
    List<RunItem> items
) {

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

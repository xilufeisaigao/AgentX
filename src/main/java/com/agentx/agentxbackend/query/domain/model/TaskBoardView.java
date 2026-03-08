package com.agentx.agentxbackend.query.domain.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TaskBoardView(
    String sessionId,
    int totalTasks,
    int activeRuns,
    List<ModuleLane> modules
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ModuleLane(
        String moduleId,
        String moduleName,
        String moduleDescription,
        List<TaskCard> tasks
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskCard(
        String taskId,
        String title,
        String taskTemplateId,
        String status,
        String activeRunId,
        String requiredToolpacksJson,
        List<String> dependencyTaskIds,
        String latestContextSnapshotId,
        String latestContextStatus,
        String latestContextRunKind,
        Instant latestContextCompiledAt,
        String lastRunId,
        String lastRunStatus,
        String lastRunKind,
        Instant lastRunUpdatedAt,
        String latestDeliveryCommit,
        String latestVerifyRunId,
        String latestVerifyStatus
    ) {
    }
}

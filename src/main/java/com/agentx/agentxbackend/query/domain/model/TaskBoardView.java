package com.agentx.agentxbackend.query.domain.model;

import java.time.Instant;
import java.util.List;

public record TaskBoardView(
    String sessionId,
    int totalTasks,
    int activeRuns,
    List<ModuleLane> modules
) {

    public record ModuleLane(
        String moduleId,
        String moduleName,
        String moduleDescription,
        List<TaskCard> tasks
    ) {
    }

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

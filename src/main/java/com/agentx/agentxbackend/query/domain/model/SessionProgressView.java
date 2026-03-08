package com.agentx.agentxbackend.query.domain.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SessionProgressView(
    String sessionId,
    String title,
    String sessionStatus,
    String phase,
    String blockerSummary,
    String primaryAction,
    RequirementSummary requirement,
    TaskCounts taskCounts,
    TicketCounts ticketCounts,
    RunCounts runCounts,
    LatestRun latestRun,
    DeliverySummary delivery,
    boolean canCompleteSession,
    List<String> completionBlockers,
    Instant createdAt,
    Instant updatedAt
) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RequirementSummary(
        String docId,
        int currentVersion,
        Integer confirmedVersion,
        String status,
        String title,
        Instant updatedAt
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TaskCounts(
        int total,
        int planned,
        int waitingDependency,
        int waitingWorker,
        int readyForAssign,
        int assigned,
        int delivered,
        int done
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TicketCounts(
        int total,
        int open,
        int inProgress,
        int waitingUser,
        int done,
        int blocked
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RunCounts(
        int total,
        int running,
        int waitingForeman,
        int succeeded,
        int failed,
        int cancelled
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LatestRun(
        String runId,
        String taskId,
        String taskTitle,
        String moduleId,
        String moduleName,
        String workerId,
        String runKind,
        String status,
        String eventType,
        String eventBody,
        Instant eventAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt
    ) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeliverySummary(
        boolean deliveryTagPresent,
        int deliveredTaskCount,
        int doneTaskCount,
        String latestDeliveryTaskId,
        String latestDeliveryCommit,
        String latestVerifyRunId,
        String latestVerifyStatus
    ) {
    }
}

package com.agentx.agentxbackend.query.infrastructure.persistence;

import com.agentx.agentxbackend.query.application.port.out.ProgressReadRepository;
import com.agentx.agentxbackend.query.application.query.SessionProgressSnapshot;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class MybatisProgressReadRepository implements ProgressReadRepository {

    private final ProgressQueryMapper mapper;

    public MybatisProgressReadRepository(ProgressQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SessionProgressSnapshot getSessionProgressSnapshot(String sessionId) {
        SessionProgressView.TaskCounts taskCounts = toTaskCounts(mapper.countTasksByStatus(sessionId));
        SessionProgressView.TicketCounts ticketCounts = toTicketCounts(mapper.countTicketsByStatus(sessionId));
        SessionProgressView.RunCounts runCounts = toRunCounts(mapper.countRunsByStatus(sessionId));
        SessionProgressView.LatestRun latestRun = toLatestRun(mapper.findLatestRun(sessionId));
        DeliveryCountsRow deliveryCountsRow = mapper.findDeliveryCounts(sessionId);
        LatestDeliveryRow latestDeliveryRow = mapper.findLatestDelivery(sessionId);

        SessionProgressView.DeliverySummary delivery = new SessionProgressView.DeliverySummary(
            false,
            deliveryCountsRow == null ? 0 : orZero(deliveryCountsRow.getDeliveredTaskCount()),
            deliveryCountsRow == null ? 0 : orZero(deliveryCountsRow.getDoneTaskCount()),
            latestDeliveryRow == null ? null : trimToNull(latestDeliveryRow.getLatestDeliveryTaskId()),
            latestDeliveryRow == null ? null : trimToNull(latestDeliveryRow.getLatestDeliveryCommit()),
            latestDeliveryRow == null ? null : trimToNull(latestDeliveryRow.getLatestVerifyRunId()),
            latestDeliveryRow == null ? null : trimToNull(latestDeliveryRow.getLatestVerifyStatus())
        );
        return new SessionProgressSnapshot(taskCounts, ticketCounts, runCounts, latestRun, delivery);
    }

    @Override
    public TicketInboxView getTicketInbox(String sessionId, String status) {
        List<TicketInboxItemRow> rows = mapper.findTicketInboxItems(sessionId, status);
        List<TicketInboxView.TicketItem> items = new ArrayList<>(rows.size());
        int waitingUserTickets = 0;
        for (TicketInboxItemRow row : rows) {
            if ("WAITING_USER".equalsIgnoreCase(row.getStatus())) {
                waitingUserTickets++;
            }
            items.add(new TicketInboxView.TicketItem(
                row.getTicketId(),
                row.getType(),
                row.getStatus(),
                row.getTitle(),
                row.getCreatedByRole(),
                row.getAssigneeRole(),
                row.getRequirementDocId(),
                row.getRequirementDocVer(),
                row.getPayloadJson(),
                row.getClaimedBy(),
                toInstant(row.getLeaseUntil()),
                toInstant(row.getCreatedAt()),
                toInstant(row.getUpdatedAt()),
                row.getLatestEventType(),
                row.getLatestEventBody(),
                row.getLatestEventDataJson(),
                toInstant(row.getLatestEventAt()),
                row.getSourceRunId(),
                row.getSourceTaskId(),
                defaultRequestKind(row),
                defaultQuestion(row),
                "WAITING_USER".equalsIgnoreCase(row.getStatus())
            ));
        }
        return new TicketInboxView(sessionId, status, items.size(), waitingUserTickets, List.copyOf(items));
    }

    @Override
    public TaskBoardView getTaskBoard(String sessionId) {
        List<TaskBoardItemRow> rows = mapper.findTaskBoardItems(sessionId);
        Map<String, ModuleAccumulator> modules = new LinkedHashMap<>();
        int activeRuns = 0;
        for (TaskBoardItemRow row : rows) {
            ModuleAccumulator module = modules.computeIfAbsent(
                row.getModuleId(),
                key -> new ModuleAccumulator(row.getModuleId(), row.getModuleName(), row.getModuleDescription())
            );
            if (trimToNull(row.getActiveRunId()) != null) {
                activeRuns++;
            }
            module.tasks.add(new TaskBoardView.TaskCard(
                row.getTaskId(),
                row.getTitle(),
                row.getTaskTemplateId(),
                row.getStatus(),
                trimToNull(row.getActiveRunId()),
                row.getRequiredToolpacksJson(),
                splitCsv(row.getDependencyTaskIdsCsv()),
                trimToNull(row.getLatestContextSnapshotId()),
                trimToNull(row.getLatestContextStatus()),
                trimToNull(row.getLatestContextRunKind()),
                toInstant(row.getLatestContextCompiledAt()),
                trimToNull(row.getLastRunId()),
                trimToNull(row.getLastRunStatus()),
                trimToNull(row.getLastRunKind()),
                toInstant(row.getLastRunUpdatedAt()),
                trimToNull(row.getLatestDeliveryCommit()),
                trimToNull(row.getLatestVerifyRunId()),
                trimToNull(row.getLatestVerifyStatus())
            ));
        }

        List<TaskBoardView.ModuleLane> moduleLanes = modules.values().stream().map(ModuleAccumulator::toView).toList();
        return new TaskBoardView(sessionId, rows.size(), activeRuns, moduleLanes);
    }

    @Override
    public RunTimelineView getRunTimeline(String sessionId, int limit) {
        List<RunTimelineItemRow> rows = mapper.findRunTimelineItems(sessionId, limit);
        List<RunTimelineView.RunItem> items = rows.stream()
            .map(row -> new RunTimelineView.RunItem(
                row.getRunId(),
                row.getTaskId(),
                row.getTaskTitle(),
                row.getModuleId(),
                row.getModuleName(),
                row.getWorkerId(),
                row.getRunKind(),
                row.getRunStatus(),
                row.getEventType(),
                row.getEventBody(),
                row.getEventDataJson(),
                toInstant(row.getEventCreatedAt()),
                toInstant(row.getStartedAt()),
                toInstant(row.getFinishedAt()),
                row.getBranchName()
            ))
            .toList();
        return new RunTimelineView(sessionId, items.size(), items);
    }

    private static SessionProgressView.TaskCounts toTaskCounts(List<StatusCountRow> rows) {
        int planned = 0;
        int waitingDependency = 0;
        int waitingWorker = 0;
        int readyForAssign = 0;
        int assigned = 0;
        int delivered = 0;
        int done = 0;
        for (StatusCountRow row : safeRows(rows)) {
            int count = orZero(row.getStatusCount());
            switch (normalize(row.getStatus())) {
                case "PLANNED" -> planned += count;
                case "WAITING_DEPENDENCY" -> waitingDependency += count;
                case "WAITING_WORKER" -> waitingWorker += count;
                case "READY_FOR_ASSIGN" -> readyForAssign += count;
                case "ASSIGNED" -> assigned += count;
                case "DELIVERED" -> delivered += count;
                case "DONE" -> done += count;
                default -> {
                }
            }
        }
        return new SessionProgressView.TaskCounts(
            planned + waitingDependency + waitingWorker + readyForAssign + assigned + delivered + done,
            planned,
            waitingDependency,
            waitingWorker,
            readyForAssign,
            assigned,
            delivered,
            done
        );
    }

    private static SessionProgressView.TicketCounts toTicketCounts(List<StatusCountRow> rows) {
        int open = 0;
        int inProgress = 0;
        int waitingUser = 0;
        int done = 0;
        int blocked = 0;
        for (StatusCountRow row : safeRows(rows)) {
            int count = orZero(row.getStatusCount());
            switch (normalize(row.getStatus())) {
                case "OPEN" -> open += count;
                case "IN_PROGRESS" -> inProgress += count;
                case "WAITING_USER" -> waitingUser += count;
                case "DONE" -> done += count;
                case "BLOCKED" -> blocked += count;
                default -> {
                }
            }
        }
        return new SessionProgressView.TicketCounts(open + inProgress + waitingUser + done + blocked, open, inProgress,
            waitingUser, done, blocked);
    }

    private static SessionProgressView.RunCounts toRunCounts(List<StatusCountRow> rows) {
        int running = 0;
        int waitingForeman = 0;
        int succeeded = 0;
        int failed = 0;
        int cancelled = 0;
        for (StatusCountRow row : safeRows(rows)) {
            int count = orZero(row.getStatusCount());
            switch (normalize(row.getStatus())) {
                case "RUNNING" -> running += count;
                case "WAITING_FOREMAN" -> waitingForeman += count;
                case "SUCCEEDED" -> succeeded += count;
                case "FAILED" -> failed += count;
                case "CANCELLED" -> cancelled += count;
                default -> {
                }
            }
        }
        return new SessionProgressView.RunCounts(
            running + waitingForeman + succeeded + failed + cancelled,
            running,
            waitingForeman,
            succeeded,
            failed,
            cancelled
        );
    }

    private static SessionProgressView.LatestRun toLatestRun(LatestRunRow row) {
        if (row == null) {
            return null;
        }
        return new SessionProgressView.LatestRun(
            row.getRunId(),
            row.getTaskId(),
            row.getTaskTitle(),
            row.getModuleId(),
            row.getModuleName(),
            row.getWorkerId(),
            row.getRunKind(),
            row.getStatus(),
            row.getEventType(),
            row.getEventBody(),
            toInstant(row.getEventAt()),
            toInstant(row.getStartedAt()),
            toInstant(row.getFinishedAt()),
            toInstant(row.getUpdatedAt())
        );
    }

    private static List<StatusCountRow> safeRows(List<StatusCountRow> rows) {
        return rows == null ? List.of() : rows;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String defaultRequestKind(TicketInboxItemRow row) {
        String requestKind = trimToNull(row.getRequestKind());
        return requestKind != null ? requestKind : trimToNull(row.getType());
    }

    private static String defaultQuestion(TicketInboxItemRow row) {
        String question = trimToNull(row.getQuestion());
        return question != null ? question : trimToNull(row.getTitle());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> splitCsv(String value) {
        String csv = trimToNull(value);
        if (csv == null) {
            return List.of();
        }
        String[] tokens = csv.split(",");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String trimmed = trimToNull(token);
            if (trimmed != null) {
                values.add(trimmed);
            }
        }
        return List.copyOf(values);
    }

    private static final class ModuleAccumulator {

        private final String moduleId;
        private final String moduleName;
        private final String moduleDescription;
        private final List<TaskBoardView.TaskCard> tasks = new ArrayList<>();

        private ModuleAccumulator(String moduleId, String moduleName, String moduleDescription) {
            this.moduleId = moduleId;
            this.moduleName = moduleName;
            this.moduleDescription = moduleDescription;
        }

        private TaskBoardView.ModuleLane toView() {
            return new TaskBoardView.ModuleLane(moduleId, moduleName, moduleDescription, List.copyOf(tasks));
        }
    }
}

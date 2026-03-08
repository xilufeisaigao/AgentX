package com.agentx.agentxbackend.execution.infrastructure.persistence;

import com.agentx.agentxbackend.execution.application.port.out.TaskRunRepository;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisTaskRunRepository implements TaskRunRepository {

    private final TaskRunMapper mapper;

    public MybatisTaskRunRepository(TaskRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaskRun save(TaskRun run) {
        int inserted = mapper.insert(toRow(run));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert task run: " + run.runId());
        }
        return run;
    }

    @Override
    public Optional<TaskRun> findById(String runId) {
        TaskRunRow row = mapper.findById(runId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public TaskRun update(TaskRun run) {
        int updated = mapper.update(toRow(run));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update task run: " + run.runId());
        }
        return run;
    }

    @Override
    public int countActiveRuns() {
        return mapper.countActiveRuns();
    }

    @Override
    public Set<String> findActiveWorkerIds(int limit) {
        int cappedLimit = limit <= 0 ? 512 : Math.min(limit, 4096);
        List<String> rows = mapper.findActiveWorkerIds(cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(rows);
    }

    @Override
    public Optional<WorkerActivity> findWorkerActivity(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        TaskRunWorkerStatsRow row = mapper.findWorkerStats(workerId.trim());
        if (row == null) {
            return Optional.empty();
        }
        Instant lastActivityAt = row.getLastActivityAt() == null
            ? null
            : row.getLastActivityAt().toInstant();
        long totalRuns = row.getTotalRuns() == null ? 0L : row.getTotalRuns();
        return Optional.of(new WorkerActivity(row.getWorkerId(), lastActivityAt, totalRuns));
    }

    @Override
    public Optional<TaskRun> findOldestRunningVerifyRunByWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        TaskRunRow row = mapper.findOldestRunningVerifyRunByWorker(workerId.trim());
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Optional<TaskRun> findLatestVerifyRunByTaskAndBaseCommit(String taskId, String baseCommit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (baseCommit == null || baseCommit.isBlank()) {
            throw new IllegalArgumentException("baseCommit must not be blank");
        }
        TaskRunRow row = mapper.findLatestVerifyRunByTaskAndBaseCommit(taskId.trim(), baseCommit.trim());
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public int countVerifyRunsByTaskAndBaseCommit(String taskId, String baseCommit) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (baseCommit == null || baseCommit.isBlank()) {
            throw new IllegalArgumentException("baseCommit must not be blank");
        }
        return mapper.countVerifyRunsByTaskAndBaseCommit(taskId.trim(), baseCommit.trim());
    }

    @Override
    public Optional<TaskRun> findLatestRunByTaskAndKind(String taskId, RunKind runKind) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (runKind == null) {
            throw new IllegalArgumentException("runKind must not be null");
        }
        TaskRunRow row = mapper.findLatestRunByTaskAndKind(taskId.trim(), runKind.name());
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public boolean existsActiveRunByTaskAndKind(String taskId, RunKind runKind) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (runKind == null) {
            throw new IllegalArgumentException("runKind must not be null");
        }
        return mapper.countActiveRunsByTaskAndKind(taskId.trim(), runKind.name()) > 0;
    }

    @Override
    public boolean existsActiveRunBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return mapper.countActiveRunsBySessionId(sessionId.trim()) > 0;
    }

    @Override
    public List<TaskRun> findExpiredActiveRuns(Instant leaseBefore, int limit) {
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 500);
        return mapper.findExpiredActiveRuns(Timestamp.from(leaseBefore), cappedLimit)
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public boolean markFailedIfLeaseExpired(String runId, Instant leaseBefore, Instant updatedAt) {
        int updated = mapper.markFailedIfLeaseExpired(
            runId,
            Timestamp.from(leaseBefore),
            Timestamp.from(updatedAt)
        );
        return updated == 1;
    }

    private TaskRunRow toRow(TaskRun run) {
        TaskRunRow row = new TaskRunRow();
        row.setRunId(run.runId());
        row.setTaskId(run.taskId());
        row.setWorkerId(run.workerId());
        row.setStatus(run.status().name());
        row.setRunKind(run.runKind().name());
        row.setContextSnapshotId(run.contextSnapshotId());
        row.setLeaseUntil(Timestamp.from(run.leaseUntil()));
        row.setLastHeartbeatAt(Timestamp.from(run.lastHeartbeatAt()));
        row.setStartedAt(Timestamp.from(run.startedAt()));
        row.setFinishedAt(run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()));
        row.setTaskSkillRef(run.taskSkillRef());
        row.setToolpacksSnapshotJson(run.toolpacksSnapshotJson());
        row.setBaseCommit(run.baseCommit());
        row.setBranchName(run.branchName());
        row.setWorktreePath(run.worktreePath());
        row.setCreatedAt(Timestamp.from(run.createdAt()));
        row.setUpdatedAt(Timestamp.from(run.updatedAt()));
        return row;
    }

    private TaskRun toDomain(TaskRunRow row) {
        return new TaskRun(
            row.getRunId(),
            row.getTaskId(),
            row.getWorkerId(),
            RunStatus.valueOf(row.getStatus()),
            RunKind.valueOf(row.getRunKind()),
            row.getContextSnapshotId(),
            row.getLeaseUntil().toInstant(),
            row.getLastHeartbeatAt().toInstant(),
            row.getStartedAt().toInstant(),
            row.getFinishedAt() == null ? null : row.getFinishedAt().toInstant(),
            row.getTaskSkillRef(),
            row.getToolpacksSnapshotJson(),
            row.getBaseCommit(),
            row.getBranchName(),
            row.getWorktreePath(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}

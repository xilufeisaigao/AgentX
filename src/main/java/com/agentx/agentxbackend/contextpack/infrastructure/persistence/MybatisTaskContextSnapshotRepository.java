package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import com.agentx.agentxbackend.contextpack.application.port.out.TaskContextSnapshotRepository;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatus;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisTaskContextSnapshotRepository implements TaskContextSnapshotRepository {

    private final TaskContextSnapshotMapper mapper;

    public MybatisTaskContextSnapshotRepository(TaskContextSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaskContextSnapshot save(TaskContextSnapshot snapshot) {
        int inserted = mapper.insert(toRow(snapshot));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert task context snapshot: " + snapshot.snapshotId());
        }
        return snapshot;
    }

    @Override
    public Optional<TaskContextSnapshot> findLatestByTaskAndRunKind(String taskId, String runKind) {
        TaskContextSnapshotRow row = mapper.findLatestByTaskAndRunKind(taskId, runKind);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Optional<TaskContextSnapshot> findLatestReadyByFingerprint(
        String taskId,
        String runKind,
        String sourceFingerprint
    ) {
        TaskContextSnapshotRow row = mapper.findLatestReadyByFingerprint(taskId, runKind, sourceFingerprint);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public List<TaskContextSnapshot> findLatestByTaskId(String taskId, int limit) {
        int cappedLimit = Math.max(1, Math.min(100, limit));
        List<TaskContextSnapshotRow> rows = mapper.findLatestByTaskId(taskId, cappedLimit);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<TaskContextSnapshot> snapshots = new ArrayList<>(rows.size());
        for (TaskContextSnapshotRow row : rows) {
            snapshots.add(toDomain(row));
        }
        return snapshots;
    }

    @Override
    public int markReadyAsStale(String taskId, String runKind, Instant updatedAt) {
        return mapper.markReadyAsStale(taskId, runKind, Timestamp.from(updatedAt));
    }

    @Override
    public boolean transitionStatus(
        String snapshotId,
        TaskContextSnapshotStatus expectedStatus,
        TaskContextSnapshotStatus nextStatus,
        Instant updatedAt
    ) {
        return mapper.transitionStatus(
            snapshotId,
            expectedStatus.name(),
            nextStatus.name(),
            Timestamp.from(updatedAt)
        ) == 1;
    }

    @Override
    public boolean markReady(
        String snapshotId,
        String taskContextRef,
        String taskSkillRef,
        Instant compiledAt,
        Instant updatedAt
    ) {
        return mapper.markReady(
            snapshotId,
            taskContextRef,
            taskSkillRef,
            Timestamp.from(compiledAt),
            Timestamp.from(updatedAt)
        ) == 1;
    }

    @Override
    public boolean markFailed(String snapshotId, String errorCode, String errorMessage, Instant updatedAt) {
        return mapper.markFailed(
            snapshotId,
            errorCode,
            errorMessage,
            Timestamp.from(updatedAt)
        ) == 1;
    }

    private TaskContextSnapshotRow toRow(TaskContextSnapshot snapshot) {
        TaskContextSnapshotRow row = new TaskContextSnapshotRow();
        row.setSnapshotId(snapshot.snapshotId());
        row.setTaskId(snapshot.taskId());
        row.setRunKind(snapshot.runKind());
        row.setStatus(snapshot.status().name());
        row.setTriggerType(snapshot.triggerType());
        row.setSourceFingerprint(snapshot.sourceFingerprint());
        row.setTaskContextRef(snapshot.taskContextRef());
        row.setTaskSkillRef(snapshot.taskSkillRef());
        row.setErrorCode(snapshot.errorCode());
        row.setErrorMessage(snapshot.errorMessage());
        row.setCompiledAt(toTimestamp(snapshot.compiledAt()));
        row.setRetainedUntil(Timestamp.from(snapshot.retainedUntil()));
        row.setCreatedAt(Timestamp.from(snapshot.createdAt()));
        row.setUpdatedAt(Timestamp.from(snapshot.updatedAt()));
        return row;
    }

    private TaskContextSnapshot toDomain(TaskContextSnapshotRow row) {
        return new TaskContextSnapshot(
            row.getSnapshotId(),
            row.getTaskId(),
            row.getRunKind(),
            TaskContextSnapshotStatus.valueOf(row.getStatus()),
            row.getTriggerType(),
            row.getSourceFingerprint(),
            row.getTaskContextRef(),
            row.getTaskSkillRef(),
            row.getErrorCode(),
            row.getErrorMessage(),
            toInstant(row.getCompiledAt()),
            row.getRetainedUntil().toInstant(),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

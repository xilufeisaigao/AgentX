package com.agentx.agentxbackend.contextpack.application.port.out;

import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshot;
import com.agentx.agentxbackend.contextpack.domain.model.TaskContextSnapshotStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskContextSnapshotRepository {

    TaskContextSnapshot save(TaskContextSnapshot snapshot);

    Optional<TaskContextSnapshot> findLatestByTaskAndRunKind(String taskId, String runKind);

    Optional<TaskContextSnapshot> findLatestReadyByFingerprint(String taskId, String runKind, String sourceFingerprint);

    List<TaskContextSnapshot> findLatestByTaskId(String taskId, int limit);

    int markReadyAsStale(String taskId, String runKind, Instant updatedAt);

    boolean transitionStatus(
        String snapshotId,
        TaskContextSnapshotStatus expectedStatus,
        TaskContextSnapshotStatus nextStatus,
        Instant updatedAt
    );

    boolean markReady(
        String snapshotId,
        String taskContextRef,
        String taskSkillRef,
        Instant compiledAt,
        Instant updatedAt
    );

    boolean markFailed(
        String snapshotId,
        String errorCode,
        String errorMessage,
        Instant updatedAt
    );
}

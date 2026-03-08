package com.agentx.agentxbackend.execution.application.port.out;

import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.execution.domain.model.RunKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface TaskRunRepository {

    TaskRun save(TaskRun run);

    Optional<TaskRun> findById(String runId);

    TaskRun update(TaskRun run);

    int countActiveRuns();

    Set<String> findActiveWorkerIds(int limit);

    Optional<WorkerActivity> findWorkerActivity(String workerId);

    Optional<TaskRun> findOldestRunningVerifyRunByWorker(String workerId);

    Optional<TaskRun> findLatestVerifyRunByTaskAndBaseCommit(String taskId, String baseCommit);

    int countVerifyRunsByTaskAndBaseCommit(String taskId, String baseCommit);

    Optional<TaskRun> findLatestRunByTaskAndKind(String taskId, RunKind runKind);

    boolean existsActiveRunByTaskAndKind(String taskId, RunKind runKind);

    boolean existsActiveRunBySessionId(String sessionId);

    List<TaskRun> findExpiredActiveRuns(Instant leaseBefore, int limit);

    boolean markFailedIfLeaseExpired(String runId, Instant leaseBefore, Instant updatedAt);

    record WorkerActivity(String workerId, Instant lastActivityAt, long totalRuns) {
    }
}

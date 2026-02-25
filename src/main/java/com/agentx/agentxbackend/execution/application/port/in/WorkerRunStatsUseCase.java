package com.agentx.agentxbackend.execution.application.port.in;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public interface WorkerRunStatsUseCase {

    Set<String> listActiveWorkerIds(int limit);

    Optional<WorkerRunStats> findWorkerRunStats(String workerId);

    record WorkerRunStats(
        String workerId,
        Instant lastActivityAt,
        long totalRuns
    ) {
    }
}

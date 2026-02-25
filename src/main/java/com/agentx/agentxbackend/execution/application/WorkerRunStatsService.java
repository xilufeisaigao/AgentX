package com.agentx.agentxbackend.execution.application;

import com.agentx.agentxbackend.execution.application.port.in.WorkerRunStatsUseCase;
import com.agentx.agentxbackend.execution.application.port.out.TaskRunRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class WorkerRunStatsService implements WorkerRunStatsUseCase {

    private final TaskRunRepository taskRunRepository;

    public WorkerRunStatsService(TaskRunRepository taskRunRepository) {
        this.taskRunRepository = taskRunRepository;
    }

    @Override
    public Set<String> listActiveWorkerIds(int limit) {
        int cappedLimit = limit <= 0 ? 512 : Math.min(limit, 4096);
        return taskRunRepository.findActiveWorkerIds(cappedLimit);
    }

    @Override
    public Optional<WorkerRunStats> findWorkerRunStats(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return taskRunRepository.findWorkerActivity(workerId.trim())
            .map(activity -> new WorkerRunStats(
                activity.workerId(),
                activity.lastActivityAt(),
                activity.totalRuns()
            ));
    }
}

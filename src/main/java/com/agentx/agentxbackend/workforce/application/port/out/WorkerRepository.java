package com.agentx.agentxbackend.workforce.application.port.out;

import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkerRepository {

    Worker save(Worker worker);

    Optional<Worker> findById(String workerId);

    Worker updateStatus(String workerId, WorkerStatus status, Instant updatedAt);

    boolean existsByStatus(WorkerStatus status);

    int countByStatus(WorkerStatus status);

    int countAll();

    List<Worker> findByStatus(WorkerStatus status, int limit);
}

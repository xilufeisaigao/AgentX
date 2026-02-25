package com.agentx.agentxbackend.workforce.infrastructure.persistence;

import com.agentx.agentxbackend.workforce.application.port.out.WorkerRepository;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisWorkerRepository implements WorkerRepository {

    private final WorkerMapper mapper;

    public MybatisWorkerRepository(WorkerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Worker save(Worker worker) {
        int inserted = mapper.insert(toRow(worker));
        if (inserted != 1) {
            throw new IllegalStateException("Failed to insert worker: " + worker.workerId());
        }
        return worker;
    }

    @Override
    public Optional<Worker> findById(String workerId) {
        WorkerRow row = mapper.findById(workerId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Worker updateStatus(String workerId, WorkerStatus status, Instant updatedAt) {
        int updated = mapper.updateStatus(workerId, status.name(), Timestamp.from(updatedAt));
        if (updated != 1) {
            throw new IllegalStateException("Failed to update worker status: " + workerId);
        }
        return findById(workerId).orElseThrow(() -> new IllegalStateException("Worker not found after status update: " + workerId));
    }

    @Override
    public boolean existsByStatus(WorkerStatus status) {
        return mapper.countByStatus(status.name()) > 0;
    }

    @Override
    public int countByStatus(WorkerStatus status) {
        return mapper.countByStatus(status.name());
    }

    @Override
    public int countAll() {
        return mapper.countAll();
    }

    @Override
    public List<Worker> findByStatus(WorkerStatus status, int limit) {
        int cappedLimit = limit <= 0 ? 100 : Math.min(limit, 1000);
        return mapper.findByStatus(status.name(), cappedLimit)
            .stream()
            .map(this::toDomain)
            .toList();
    }

    private WorkerRow toRow(Worker worker) {
        WorkerRow row = new WorkerRow();
        row.setWorkerId(worker.workerId());
        row.setStatus(worker.status().name());
        row.setCreatedAt(Timestamp.from(worker.createdAt()));
        row.setUpdatedAt(Timestamp.from(worker.updatedAt()));
        return row;
    }

    private Worker toDomain(WorkerRow row) {
        return new Worker(
            row.getWorkerId(),
            WorkerStatus.valueOf(row.getStatus()),
            row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant()
        );
    }
}

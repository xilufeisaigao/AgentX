package com.agentx.agentxbackend.workforce.application;

import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.application.port.out.ToolpackRepository;
import com.agentx.agentxbackend.workforce.application.port.out.WorkerRepository;
import com.agentx.agentxbackend.workforce.application.port.out.WorkerToolpackRepository;
import com.agentx.agentxbackend.workforce.domain.model.Toolpack;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkerCapabilityService implements WorkerCapabilityUseCase {

    private static final Set<String> ALLOWED_TOOLPACK_KINDS = Set.of(
        "language",
        "build",
        "compiler",
        "script",
        "misc"
    );

    private final WorkerRepository workerRepository;
    private final ToolpackRepository toolpackRepository;
    private final WorkerToolpackRepository workerToolpackRepository;

    public WorkerCapabilityService(
        WorkerRepository workerRepository,
        ToolpackRepository toolpackRepository,
        WorkerToolpackRepository workerToolpackRepository
    ) {
        this.workerRepository = workerRepository;
        this.toolpackRepository = toolpackRepository;
        this.workerToolpackRepository = workerToolpackRepository;
    }

    @Override
    public Toolpack registerToolpack(String toolpackId, String name, String version, String kind, String description) {
        String normalizedName = requireNotBlank(name, "name");
        String normalizedVersion = requireNotBlank(version, "version");
        String normalizedKind = normalizeToolpackKind(kind);
        String normalizedDescription = normalizeNullable(description);
        String normalizedToolpackId = normalizeOrGenerateId(toolpackId, "TP-");

        Optional<Toolpack> existingById = toolpackRepository.findById(normalizedToolpackId);
        if (existingById.isPresent()) {
            Toolpack existing = existingById.get();
            if (!existing.name().equals(normalizedName)
                || !existing.version().equals(normalizedVersion)
                || !existing.kind().equals(normalizedKind)) {
                throw new IllegalStateException("toolpackId already exists with different immutable fields: " + normalizedToolpackId);
            }
            return existing;
        }

        Optional<Toolpack> existingByNameVersion = toolpackRepository.findByNameAndVersion(
            normalizedName,
            normalizedVersion
        );
        if (existingByNameVersion.isPresent()) {
            return existingByNameVersion.get();
        }

        Toolpack toolpack = new Toolpack(
            normalizedToolpackId,
            normalizedName,
            normalizedVersion,
            normalizedKind,
            normalizedDescription,
            Instant.now()
        );
        return toolpackRepository.save(toolpack);
    }

    @Override
    public List<Toolpack> listToolpacks() {
        return toolpackRepository.findAll();
    }

    @Override
    public Worker registerWorker(String workerId) {
        String normalizedWorkerId = normalizeOrGenerateId(workerId, "WRK-");
        Optional<Worker> existing = workerRepository.findById(normalizedWorkerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = Instant.now();
        Worker created = new Worker(
            normalizedWorkerId,
            WorkerStatus.PROVISIONING,
            now,
            now
        );
        return workerRepository.save(created);
    }

    @Override
    public Worker updateWorkerStatus(String workerId, WorkerStatus status) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        Worker current = workerRepository.findById(normalizedWorkerId)
            .orElseThrow(() -> new NoSuchElementException("Worker not found: " + normalizedWorkerId));
        if (current.status() == status) {
            return current;
        }
        if (!isAllowedStatusTransition(current.status(), status)) {
            throw new IllegalStateException(
                "Illegal worker status transition: " + current.status() + " -> " + status + ", workerId=" + normalizedWorkerId
            );
        }
        return workerRepository.updateStatus(normalizedWorkerId, status, Instant.now());
    }

    @Override
    public void bindToolpacks(String workerId, List<String> toolpackIds) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        Worker worker = workerRepository.findById(normalizedWorkerId)
            .orElseThrow(() -> new NoSuchElementException("Worker not found: " + normalizedWorkerId));
        if (worker.status() == WorkerStatus.DISABLED) {
            throw new IllegalStateException("Cannot bind toolpacks to disabled worker: " + normalizedWorkerId);
        }

        List<String> normalizedToolpackIds = normalizeToolpackIds(toolpackIds);
        if (normalizedToolpackIds.isEmpty()) {
            throw new IllegalArgumentException("toolpackIds must not be empty");
        }

        for (String toolpackId : normalizedToolpackIds) {
            if (toolpackRepository.findById(toolpackId).isEmpty()) {
                throw new NoSuchElementException("Toolpack not found: " + toolpackId);
            }
        }
        for (String toolpackId : normalizedToolpackIds) {
            workerToolpackRepository.bind(normalizedWorkerId, toolpackId);
        }
    }

    @Override
    public boolean hasEligibleWorker(List<String> requiredToolpacks) {
        List<String> normalizedRequired = normalizeToolpackIds(requiredToolpacks);
        if (normalizedRequired.isEmpty()) {
            return workerRepository.existsByStatus(WorkerStatus.READY);
        }
        return workerToolpackRepository.existsReadyWorkerCoveringAll(normalizedRequired);
    }

    @Override
    public boolean isWorkerEligible(String workerId, List<String> requiredToolpacks) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        Worker worker = workerRepository.findById(normalizedWorkerId).orElse(null);
        if (worker == null || worker.status() != WorkerStatus.READY) {
            return false;
        }
        List<String> normalizedRequired = normalizeToolpackIds(requiredToolpacks);
        if (normalizedRequired.isEmpty()) {
            return true;
        }
        List<String> workerToolpackIds = workerToolpackRepository.findToolpackIdsByWorkerId(normalizedWorkerId);
        Set<String> bound = new LinkedHashSet<>(workerToolpackIds);
        return bound.containsAll(normalizedRequired);
    }

    @Override
    public boolean workerExists(String workerId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        return workerRepository.findById(normalizedWorkerId).isPresent();
    }

    @Override
    public int countWorkers() {
        return workerRepository.countAll();
    }

    @Override
    public int countWorkersByStatus(WorkerStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return workerRepository.countByStatus(status);
    }

    @Override
    public List<String> listToolpackIdsByWorker(String workerId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        if (workerRepository.findById(normalizedWorkerId).isEmpty()) {
            throw new NoSuchElementException("Worker not found: " + normalizedWorkerId);
        }
        return workerToolpackRepository.findToolpackIdsByWorkerId(normalizedWorkerId);
    }

    @Override
    public List<Toolpack> listToolpacksByWorker(String workerId) {
        String normalizedWorkerId = requireNotBlank(workerId, "workerId");
        if (workerRepository.findById(normalizedWorkerId).isEmpty()) {
            throw new NoSuchElementException("Worker not found: " + normalizedWorkerId);
        }
        return toolpackRepository.findByWorkerId(normalizedWorkerId);
    }

    @Override
    public List<Worker> listWorkersByStatus(WorkerStatus status, int limit) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        return workerRepository.findByStatus(status, limit);
    }

    private static boolean isAllowedStatusTransition(WorkerStatus from, WorkerStatus to) {
        if (from == WorkerStatus.PROVISIONING) {
            return to == WorkerStatus.READY || to == WorkerStatus.DISABLED;
        }
        if (from == WorkerStatus.READY) {
            return to == WorkerStatus.DISABLED;
        }
        return false;
    }

    private static String normalizeToolpackKind(String kind) {
        String normalized = requireNotBlank(kind, "kind").toLowerCase(Locale.ROOT);
        if (!ALLOWED_TOOLPACK_KINDS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported toolpack kind: " + kind);
        }
        return normalized;
    }

    private static List<String> normalizeToolpackIds(List<String> toolpackIds) {
        if (toolpackIds == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String raw : toolpackIds) {
            if (raw == null) {
                continue;
            }
            String normalized = raw.trim();
            if (!normalized.isEmpty()) {
                ids.add(normalized);
            }
        }
        return new ArrayList<>(ids);
    }

    private static String normalizeNullable(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeOrGenerateId(String raw, String prefix) {
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}

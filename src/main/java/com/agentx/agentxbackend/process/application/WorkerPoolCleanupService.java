package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.port.in.WorkerRunStatsUseCase;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class WorkerPoolCleanupService {

    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final WorkerRunStatsUseCase workerRunStatsUseCase;
    private final int maxWorkersTotal;
    private final int minIdleSeconds;
    private final int maxDisablePerPoll;
    private final int scanLimit;
    private final CleanupStrategy cleanupStrategy;
    private final Set<String> reservedWorkerIds;

    public WorkerPoolCleanupService(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        WorkerRunStatsUseCase workerRunStatsUseCase,
        @Value("${agentx.workforce.worker-pool-cleanup.max-workers-total:32}") int maxWorkersTotal,
        @Value("${agentx.workforce.worker-pool-cleanup.min-idle-seconds:1800}") int minIdleSeconds,
        @Value("${agentx.workforce.worker-pool-cleanup.max-disable-per-poll:2}") int maxDisablePerPoll,
        @Value("${agentx.workforce.worker-pool-cleanup.scan-limit:256}") int scanLimit,
        @Value("${agentx.workforce.worker-pool-cleanup.strategy:oldest_idle}") String cleanupStrategy,
        @Value("${agentx.workforce.worker-pool-cleanup.reserved-worker-ids:WRK-BOOT-JAVA-CORE,WRK-BOOT-JAVA-DB,WRK-BOOT-PYTHON-AUX}") String reservedWorkerIds
    ) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.workerRunStatsUseCase = workerRunStatsUseCase;
        this.maxWorkersTotal = Math.max(1, maxWorkersTotal);
        this.minIdleSeconds = Math.max(0, minIdleSeconds);
        this.maxDisablePerPoll = Math.max(1, maxDisablePerPoll);
        this.scanLimit = Math.max(8, Math.min(scanLimit, 4096));
        this.cleanupStrategy = CleanupStrategy.fromValue(cleanupStrategy);
        this.reservedWorkerIds = parseReservedWorkerIds(reservedWorkerIds);
    }

    public CleanupResult cleanupOnce(int requestedDisableLimit) {
        int disableBudget = requestedDisableLimit <= 0
            ? maxDisablePerPoll
            : Math.min(Math.max(1, requestedDisableLimit), maxDisablePerPoll);
        Instant now = Instant.now();

        int totalWorkers = workerCapabilityUseCase.countWorkers();
        List<Worker> readyWorkers = workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, scanLimit);
        if (readyWorkers.isEmpty()) {
            return new CleanupResult(totalWorkers, 0, 0, 0, 0, List.of());
        }
        int overCapacity = Math.max(0, totalWorkers - maxWorkersTotal);
        Set<String> activeWorkerIds = workerRunStatsUseCase.listActiveWorkerIds(Math.max(scanLimit * 2, 512));

        List<CleanupCandidate> idleCandidates = new ArrayList<>();
        int skippedActive = 0;
        int skippedTooFresh = 0;
        for (Worker worker : readyWorkers) {
            if (reservedWorkerIds.contains(worker.workerId())) {
                continue;
            }
            if (activeWorkerIds.contains(worker.workerId())) {
                skippedActive++;
                continue;
            }
            WorkerRunStatsUseCase.WorkerRunStats stats = workerRunStatsUseCase.findWorkerRunStats(worker.workerId())
                .orElse(null);
            Instant lastActivityAt = resolveLastActivity(worker, stats);
            long totalRuns = stats == null ? 0L : Math.max(0L, stats.totalRuns());
            long idleSeconds = Math.max(0L, Duration.between(lastActivityAt, now).getSeconds());
            if (idleSeconds < minIdleSeconds && overCapacity <= 0) {
                skippedTooFresh++;
                continue;
            }
            idleCandidates.add(new CleanupCandidate(worker, lastActivityAt, idleSeconds, totalRuns));
        }
        if (idleCandidates.isEmpty()) {
            return new CleanupResult(totalWorkers, readyWorkers.size(), overCapacity, skippedActive, skippedTooFresh, List.of());
        }

        idleCandidates.sort(candidateComparator(cleanupStrategy));
        int toDisable = Math.min(
            disableBudget,
            Math.max(overCapacity, idleCandidates.size())
        );
        List<String> disabledWorkerIds = new ArrayList<>();
        for (CleanupCandidate candidate : idleCandidates) {
            if (disabledWorkerIds.size() >= toDisable) {
                break;
            }
            if (candidate.idleSeconds() < minIdleSeconds && disabledWorkerIds.size() >= overCapacity) {
                continue;
            }
            try {
                workerCapabilityUseCase.updateWorkerStatus(candidate.worker().workerId(), WorkerStatus.DISABLED);
                disabledWorkerIds.add(candidate.worker().workerId());
            } catch (RuntimeException ignored) {
                // Best-effort: skip worker if status transition failed due to concurrent changes.
            }
        }

        return new CleanupResult(
            totalWorkers,
            readyWorkers.size(),
            overCapacity,
            skippedActive,
            skippedTooFresh,
            List.copyOf(disabledWorkerIds)
        );
    }

    private static Instant resolveLastActivity(Worker worker, WorkerRunStatsUseCase.WorkerRunStats stats) {
        if (stats != null && stats.lastActivityAt() != null) {
            return stats.lastActivityAt();
        }
        if (worker.updatedAt() != null) {
            return worker.updatedAt();
        }
        return worker.createdAt() == null ? Instant.EPOCH : worker.createdAt();
    }

    private static Comparator<CleanupCandidate> candidateComparator(CleanupStrategy strategy) {
        Comparator<CleanupCandidate> fallback = Comparator
            .comparingLong(CleanupCandidate::idleSeconds).reversed()
            .thenComparingLong(CleanupCandidate::totalRuns)
            .thenComparing(candidate -> candidate.worker().updatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(candidate -> candidate.worker().workerId());
        if (strategy == CleanupStrategy.LEAST_USED) {
            return Comparator
                .comparingLong(CleanupCandidate::totalRuns)
                .thenComparing(Comparator.comparingLong(CleanupCandidate::idleSeconds).reversed())
                .thenComparing(candidate -> candidate.worker().updatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(candidate -> candidate.worker().workerId());
        }
        return fallback;
    }

    private static Set<String> parseReservedWorkerIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            if (token == null) {
                continue;
            }
            String normalized = token.trim();
            if (!normalized.isEmpty()) {
                ids.add(normalized);
            }
        }
        return ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    public record CleanupResult(
        int totalWorkers,
        int scannedReadyWorkers,
        int overCapacityWorkers,
        int skippedActiveWorkers,
        int skippedTooFreshWorkers,
        List<String> disabledWorkerIds
    ) {
        public int disabledWorkers() {
            return disabledWorkerIds == null ? 0 : disabledWorkerIds.size();
        }
    }

    private enum CleanupStrategy {
        OLDEST_IDLE,
        LEAST_USED;

        static CleanupStrategy fromValue(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if ("least_used".equals(normalized) || "least-used".equals(normalized)) {
                return LEAST_USED;
            }
            return OLDEST_IDLE;
        }
    }

    private record CleanupCandidate(
        Worker worker,
        Instant lastActivityAt,
        long idleSeconds,
        long totalRuns
    ) {
    }
}

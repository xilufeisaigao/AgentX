package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.execution.application.port.in.RunLeaseRecoveryUseCase;
import com.agentx.agentxbackend.process.application.WorkerAutoProvisionService;
import com.agentx.agentxbackend.process.application.WorkerPoolCleanupService;
import com.agentx.agentxbackend.process.application.WorkerRuntimeAutoRunService;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
public class WorkforceAutomationController {

    private final WorkerAutoProvisionService workerAutoProvisionService;
    private final RunLeaseRecoveryUseCase runLeaseRecoveryUseCase;
    private final WorkerRuntimeAutoRunService workerRuntimeAutoRunService;
    private final WorkerPoolCleanupService workerPoolCleanupService;
    private final WorkerCapabilityUseCase workerCapabilityUseCase;

    public WorkforceAutomationController(
        WorkerAutoProvisionService workerAutoProvisionService,
        RunLeaseRecoveryUseCase runLeaseRecoveryUseCase,
        WorkerRuntimeAutoRunService workerRuntimeAutoRunService,
        WorkerPoolCleanupService workerPoolCleanupService,
        WorkerCapabilityUseCase workerCapabilityUseCase
    ) {
        this.workerAutoProvisionService = workerAutoProvisionService;
        this.runLeaseRecoveryUseCase = runLeaseRecoveryUseCase;
        this.workerRuntimeAutoRunService = workerRuntimeAutoRunService;
        this.workerPoolCleanupService = workerPoolCleanupService;
        this.workerCapabilityUseCase = workerCapabilityUseCase;
    }

    @PostMapping("/api/v0/workforce/auto-provision")
    public ResponseEntity<AutoProvisionResponse> autoProvision(@RequestBody(required = false) AutoProvisionRequest request) {
        int maxTasks = request == null || request.maxTasks() == null ? 64 : request.maxTasks();
        WorkerAutoProvisionService.AutoProvisionResult result = workerAutoProvisionService.provisionForWaitingTasks(maxTasks);
        return ResponseEntity.ok(new AutoProvisionResponse(
            result.scannedWaitingTasks(),
            result.createdWorkers(),
            result.skippedTooFresh(),
            result.skippedMissingToolpacks(),
            result.skippedByCapacity(),
            result.createdClarificationTickets(),
            result.createdWorkerIds(),
            result.createdClarificationTicketIds()
        ));
    }

    @PostMapping("/api/v0/execution/lease-recovery")
    public ResponseEntity<LeaseRecoveryResponse> recoverExpiredRuns(
        @RequestBody(required = false) LeaseRecoveryRequest request
    ) {
        int maxRuns = request == null || request.maxRuns() == null ? 64 : request.maxRuns();
        int recovered = runLeaseRecoveryUseCase.recoverExpiredRuns(maxRuns);
        return ResponseEntity.ok(new LeaseRecoveryResponse(recovered));
    }

    @PostMapping("/api/v0/workforce/runtime/auto-run")
    public ResponseEntity<WorkerRuntimeAutoRunResponse> autoRun(@RequestBody(required = false) WorkerRuntimeAutoRunRequest request) {
        int maxWorkers = request == null || request.maxWorkers() == null ? 8 : request.maxWorkers();
        WorkerRuntimeAutoRunService.AutoRunResult result = workerRuntimeAutoRunService.runOnce(maxWorkers);
        return ResponseEntity.ok(new WorkerRuntimeAutoRunResponse(
            result.scannedReadyWorkers(),
            result.claimedRuns(),
            result.succeededRuns(),
            result.needInputRuns(),
            result.failedRuns()
        ));
    }

    @PostMapping("/api/v0/workforce/cleanup")
    public ResponseEntity<WorkerPoolCleanupResponse> cleanup(@RequestBody(required = false) WorkerPoolCleanupRequest request) {
        int maxDisable = request == null || request.maxDisable() == null ? 0 : request.maxDisable();
        WorkerPoolCleanupService.CleanupResult result = workerPoolCleanupService.cleanupOnce(maxDisable);
        return ResponseEntity.ok(new WorkerPoolCleanupResponse(
            result.totalWorkers(),
            result.scannedReadyWorkers(),
            result.overCapacityWorkers(),
            result.skippedActiveWorkers(),
            result.skippedTooFreshWorkers(),
            result.disabledWorkers(),
            result.disabledWorkerIds()
        ));
    }

    @GetMapping("/api/v0/workforce/workers")
    public ResponseEntity<WorkerListResponse> listWorkers(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer limit
    ) {
        int cappedLimit = limit == null ? 256 : Math.max(1, Math.min(limit, 1000));
        Set<WorkerStatus> filterStatuses = parseStatuses(status);
        List<WorkerView> workers = new ArrayList<>();
        for (WorkerStatus workerStatus : WorkerStatus.values()) {
            if (!filterStatuses.contains(workerStatus)) {
                continue;
            }
            List<Worker> rows = workerCapabilityUseCase.listWorkersByStatus(workerStatus, cappedLimit);
            for (Worker row : rows) {
                workers.add(new WorkerView(
                    row.workerId(),
                    row.status().name(),
                    row.createdAt(),
                    row.updatedAt(),
                    workerCapabilityUseCase.listToolpackIdsByWorker(row.workerId())
                ));
            }
        }
        workers.sort((left, right) -> {
            int statusOrder = left.status().compareTo(right.status());
            if (statusOrder != 0) {
                return statusOrder;
            }
            int updatedOrder = right.updatedAt().compareTo(left.updatedAt());
            if (updatedOrder != 0) {
                return updatedOrder;
            }
            return left.workerId().compareTo(right.workerId());
        });
        return ResponseEntity.ok(new WorkerListResponse(
            workerCapabilityUseCase.countWorkers(),
            workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.READY),
            workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.PROVISIONING),
            workerCapabilityUseCase.countWorkersByStatus(WorkerStatus.DISABLED),
            workers
        ));
    }

    public record AutoProvisionRequest(@JsonProperty("max_tasks") Integer maxTasks) {
    }

    public record LeaseRecoveryRequest(@JsonProperty("max_runs") Integer maxRuns) {
    }

    public record WorkerRuntimeAutoRunRequest(@JsonProperty("max_workers") Integer maxWorkers) {
    }

    public record WorkerPoolCleanupRequest(@JsonProperty("max_disable") Integer maxDisable) {
    }

    public record AutoProvisionResponse(
        @JsonProperty("scanned_waiting_tasks") int scannedWaitingTasks,
        @JsonProperty("created_workers") int createdWorkers,
        @JsonProperty("skipped_too_fresh") int skippedTooFresh,
        @JsonProperty("skipped_missing_toolpacks") int skippedMissingToolpacks,
        @JsonProperty("skipped_by_capacity") int skippedByCapacity,
        @JsonProperty("created_clarification_tickets") int createdClarificationTickets,
        @JsonProperty("created_worker_ids") List<String> createdWorkerIds,
        @JsonProperty("created_clarification_ticket_ids") List<String> createdClarificationTicketIds
    ) {
    }

    public record LeaseRecoveryResponse(@JsonProperty("recovered_runs") int recoveredRuns) {
    }

    public record WorkerRuntimeAutoRunResponse(
        @JsonProperty("scanned_ready_workers") int scannedReadyWorkers,
        @JsonProperty("claimed_runs") int claimedRuns,
        @JsonProperty("succeeded_runs") int succeededRuns,
        @JsonProperty("need_input_runs") int needInputRuns,
        @JsonProperty("failed_runs") int failedRuns
    ) {
    }

    public record WorkerPoolCleanupResponse(
        @JsonProperty("total_workers") int totalWorkers,
        @JsonProperty("scanned_ready_workers") int scannedReadyWorkers,
        @JsonProperty("over_capacity_workers") int overCapacityWorkers,
        @JsonProperty("skipped_active_workers") int skippedActiveWorkers,
        @JsonProperty("skipped_too_fresh_workers") int skippedTooFreshWorkers,
        @JsonProperty("disabled_workers") int disabledWorkers,
        @JsonProperty("disabled_worker_ids") List<String> disabledWorkerIds
    ) {
    }

    public record WorkerListResponse(
        @JsonProperty("total_workers") int totalWorkers,
        @JsonProperty("ready_workers") int readyWorkers,
        @JsonProperty("provisioning_workers") int provisioningWorkers,
        @JsonProperty("disabled_workers") int disabledWorkers,
        List<WorkerView> workers
    ) {
    }

    public record WorkerView(
        @JsonProperty("worker_id") String workerId,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("toolpack_ids") List<String> toolpackIds
    ) {
    }

    private static Set<WorkerStatus> parseStatuses(String statusCsv) {
        if (statusCsv == null || statusCsv.isBlank()) {
            return Set.of(WorkerStatus.values());
        }
        Set<WorkerStatus> parsed = java.util.Arrays.stream(statusCsv.split(","))
            .map(token -> token == null ? "" : token.trim())
            .filter(token -> !token.isBlank())
            .map(token -> WorkerStatus.valueOf(token.toUpperCase(Locale.ROOT)))
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (parsed.isEmpty()) {
            return Set.of(WorkerStatus.values());
        }
        return Set.copyOf(parsed);
    }
}

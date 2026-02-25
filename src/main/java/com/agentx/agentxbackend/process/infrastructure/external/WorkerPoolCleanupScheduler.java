package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.WorkerPoolCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkerPoolCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoolCleanupScheduler.class);

    private final WorkerPoolCleanupService workerPoolCleanupService;
    private final boolean enabled;

    public WorkerPoolCleanupScheduler(
        WorkerPoolCleanupService workerPoolCleanupService,
        @Value("${agentx.workforce.worker-pool-cleanup.enabled:true}") boolean enabled
    ) {
        this.workerPoolCleanupService = workerPoolCleanupService;
        this.enabled = enabled;
    }

    @Scheduled(
        initialDelayString = "${agentx.workforce.worker-pool-cleanup.initial-delay-ms:120000}",
        fixedDelayString = "${agentx.workforce.worker-pool-cleanup.poll-interval-ms:300000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            WorkerPoolCleanupService.CleanupResult result = workerPoolCleanupService.cleanupOnce(0);
            if (result.disabledWorkers() > 0) {
                log.info(
                    "Worker pool cleanup disabled idle workers, disabled={}, scannedReady={}, overCapacity={}, skippedActive={}, skippedTooFresh={}",
                    result.disabledWorkers(),
                    result.scannedReadyWorkers(),
                    result.overCapacityWorkers(),
                    result.skippedActiveWorkers(),
                    result.skippedTooFreshWorkers()
                );
            }
        } catch (Exception ex) {
            log.error("Worker pool cleanup poll failed", ex);
        }
    }
}

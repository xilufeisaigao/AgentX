package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.WorkerAutoProvisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkerAutoProvisionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerAutoProvisionScheduler.class);

    private final WorkerAutoProvisionService workerAutoProvisionService;
    private final boolean enabled;
    private final int maxTasksPerPoll;

    public WorkerAutoProvisionScheduler(
        WorkerAutoProvisionService workerAutoProvisionService,
        @Value("${agentx.workforce.auto-provisioner.enabled:true}") boolean enabled,
        @Value("${agentx.workforce.auto-provisioner.max-tasks-per-poll:64}") int maxTasksPerPoll
    ) {
        this.workerAutoProvisionService = workerAutoProvisionService;
        this.enabled = enabled;
        this.maxTasksPerPoll = Math.max(1, maxTasksPerPoll);
    }

    @Scheduled(
        initialDelayString = "${agentx.workforce.auto-provisioner.initial-delay-ms:3000}",
        fixedDelayString = "${agentx.workforce.auto-provisioner.poll-interval-ms:3000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            WorkerAutoProvisionService.AutoProvisionResult result = workerAutoProvisionService
                .provisionForWaitingTasks(maxTasksPerPoll);
            if (result.createdWorkers() > 0) {
                log.info(
                    "Auto-provisioned worker(s), created={}, workers={}, scanned={}, skippedTooFresh={}, skippedMissingToolpacks={}, skippedByCapacity={}",
                    result.createdWorkers(),
                    result.createdWorkerIds(),
                    result.scannedWaitingTasks(),
                    result.skippedTooFresh(),
                    result.skippedMissingToolpacks(),
                    result.skippedByCapacity()
                );
            }
        } catch (Exception ex) {
            log.error("Worker auto-provision poll failed", ex);
        }
    }
}

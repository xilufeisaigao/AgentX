package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.WorkerRuntimeAutoRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkerRuntimeScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntimeScheduler.class);

    private final WorkerRuntimeAutoRunService workerRuntimeAutoRunService;
    private final boolean enabled;
    private final int maxWorkersPerPoll;

    public WorkerRuntimeScheduler(
        WorkerRuntimeAutoRunService workerRuntimeAutoRunService,
        @Value("${agentx.worker-runtime.enabled:false}") boolean enabled,
        @Value("${agentx.worker-runtime.max-workers-per-poll:8}") int maxWorkersPerPoll
    ) {
        this.workerRuntimeAutoRunService = workerRuntimeAutoRunService;
        this.enabled = enabled;
        this.maxWorkersPerPoll = Math.max(1, maxWorkersPerPoll);
    }

    @Scheduled(
        initialDelayString = "${agentx.worker-runtime.initial-delay-ms:5000}",
        fixedDelayString = "${agentx.worker-runtime.poll-interval-ms:5000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            WorkerRuntimeAutoRunService.AutoRunResult result = workerRuntimeAutoRunService.runOnce(maxWorkersPerPoll);
            if (result.claimedRuns() > 0 || result.failedRuns() > 0 || result.needInputRuns() > 0) {
                log.info(
                    "Worker runtime poll result, scannedReadyWorkers={}, claimedRuns={}, succeededRuns={}, needInputRuns={}, failedRuns={}",
                    result.scannedReadyWorkers(),
                    result.claimedRuns(),
                    result.succeededRuns(),
                    result.needInputRuns(),
                    result.failedRuns()
                );
            }
        } catch (Exception ex) {
            log.error("Worker runtime poll failed", ex);
        }
    }
}


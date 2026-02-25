package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.RuntimeGarbageCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeGarbageCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGarbageCollectionScheduler.class);

    private final RuntimeGarbageCollectionService runtimeGarbageCollectionService;
    private final boolean enabled;

    public RuntimeGarbageCollectionScheduler(
        RuntimeGarbageCollectionService runtimeGarbageCollectionService,
        @Value("${agentx.process.runtime-garbage-collector.enabled:true}") boolean enabled
    ) {
        this.runtimeGarbageCollectionService = runtimeGarbageCollectionService;
        this.enabled = enabled;
    }

    @Scheduled(
        initialDelayString = "${agentx.process.runtime-garbage-collector.initial-delay-ms:10000}",
        fixedDelayString = "${agentx.process.runtime-garbage-collector.poll-interval-ms:10000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            RuntimeGarbageCollectionService.CleanupResult result = runtimeGarbageCollectionService.collectOnce();
            if (result.repositoryRecovered()
                || result.releasedAssignments() > 0
                || result.kickedMergeGates() > 0
                || result.failedAssignmentReleases() > 0
                || result.mergeGateFailures() > 0
                || result.repositoryRecoveryFailures() > 0) {
                log.info(
                    "Runtime garbage collector result, repoRecovered={}, repoRecoveryFailures={}, assignedReleased={}/{}, assignedReleaseFailures={}, deliveredStale={}/{}, mergeGateKicked={}, mergeGateRejected={}, mergeGateFailures={}",
                    result.repositoryRecovered(),
                    result.repositoryRecoveryFailures(),
                    result.releasedAssignments(),
                    result.scannedAssignedTasks(),
                    result.failedAssignmentReleases(),
                    result.staleDeliveredTasks(),
                    result.scannedDeliveredTasks(),
                    result.kickedMergeGates(),
                    result.mergeGateRejected(),
                    result.mergeGateFailures()
                );
            }
        } catch (Exception ex) {
            log.error("Runtime garbage collector poll failed", ex);
        }
    }
}

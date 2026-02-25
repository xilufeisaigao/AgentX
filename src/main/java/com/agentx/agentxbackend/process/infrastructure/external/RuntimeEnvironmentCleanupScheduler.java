package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.port.out.RuntimeEnvironmentMaintenancePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RuntimeEnvironmentCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEnvironmentCleanupScheduler.class);

    private final RuntimeEnvironmentMaintenancePort runtimeEnvironmentMaintenancePort;
    private final boolean enabled;
    private final long projectTtlHours;
    private final int maxDeletePerPoll;

    public RuntimeEnvironmentCleanupScheduler(
        RuntimeEnvironmentMaintenancePort runtimeEnvironmentMaintenancePort,
        @Value("${agentx.workforce.runtime-environment.cleanup.enabled:true}") boolean enabled,
        @Value("${agentx.workforce.runtime-environment.cleanup.project-ttl-hours:168}") long projectTtlHours,
        @Value("${agentx.workforce.runtime-environment.cleanup.max-delete-per-poll:16}") int maxDeletePerPoll
    ) {
        this.runtimeEnvironmentMaintenancePort = runtimeEnvironmentMaintenancePort;
        this.enabled = enabled;
        this.projectTtlHours = Math.max(1L, projectTtlHours);
        this.maxDeletePerPoll = Math.max(1, maxDeletePerPoll);
    }

    @Scheduled(
        initialDelayString = "${agentx.workforce.runtime-environment.cleanup.initial-delay-ms:60000}",
        fixedDelayString = "${agentx.workforce.runtime-environment.cleanup.poll-interval-ms:3600000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            RuntimeEnvironmentMaintenancePort.CleanupResult result = runtimeEnvironmentMaintenancePort
                .cleanupExpiredProjectEnvironments(Duration.ofHours(projectTtlHours), maxDeletePerPoll);
            if (result.deletedEnvironments() > 0 || result.failedEnvironments() > 0) {
                log.info(
                    "Runtime environment cleanup finished, scanned={}, deleted={}, failed={}",
                    result.scannedEnvironments(),
                    result.deletedEnvironments(),
                    result.failedEnvironments()
                );
            }
        } catch (Exception ex) {
            log.error("Runtime environment cleanup poll failed", ex);
        }
    }
}


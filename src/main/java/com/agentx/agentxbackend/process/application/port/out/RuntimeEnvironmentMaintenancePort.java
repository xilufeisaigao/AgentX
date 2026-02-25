package com.agentx.agentxbackend.process.application.port.out;

import java.time.Duration;

public interface RuntimeEnvironmentMaintenancePort {

    CleanupResult cleanupExpiredProjectEnvironments(Duration ttl, int maxDeletePerCycle);

    record CleanupResult(
        int scannedEnvironments,
        int deletedEnvironments,
        int failedEnvironments
    ) {
    }
}


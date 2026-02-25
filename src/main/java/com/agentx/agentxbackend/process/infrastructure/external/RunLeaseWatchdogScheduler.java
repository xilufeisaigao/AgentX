package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.in.RunLeaseRecoveryUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RunLeaseWatchdogScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunLeaseWatchdogScheduler.class);

    private final RunLeaseRecoveryUseCase runLeaseRecoveryUseCase;
    private final boolean enabled;
    private final int maxRunsPerPoll;

    public RunLeaseWatchdogScheduler(
        RunLeaseRecoveryUseCase runLeaseRecoveryUseCase,
        @Value("${agentx.execution.lease-watchdog.enabled:true}") boolean enabled,
        @Value("${agentx.execution.lease-watchdog.max-runs-per-poll:64}") int maxRunsPerPoll
    ) {
        this.runLeaseRecoveryUseCase = runLeaseRecoveryUseCase;
        this.enabled = enabled;
        this.maxRunsPerPoll = Math.max(1, maxRunsPerPoll);
    }

    @Scheduled(
        initialDelayString = "${agentx.execution.lease-watchdog.initial-delay-ms:5000}",
        fixedDelayString = "${agentx.execution.lease-watchdog.poll-interval-ms:5000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int recovered = runLeaseRecoveryUseCase.recoverExpiredRuns(maxRunsPerPoll);
            if (recovered > 0) {
                log.info("Recovered expired run(s), count={}", recovered);
            }
        } catch (Exception ex) {
            log.error("Run lease watchdog poll failed", ex);
        }
    }
}

package com.agentx.platform.runtime.application.workflow;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CodingSessionScheduler {

    private final CodingSessionService codingSessionService;
    private final com.agentx.platform.runtime.support.RuntimeInfrastructureProperties runtimeProperties;

    public CodingSessionScheduler(
            CodingSessionService codingSessionService,
            com.agentx.platform.runtime.support.RuntimeInfrastructureProperties runtimeProperties
    ) {
        this.codingSessionService = codingSessionService;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${agentx.platform.runtime.heartbeat-interval:PT5S}').toMillis()}")
    public void advance() {
        if (!runtimeProperties.isDriverEnabled()) {
            return;
        }
        codingSessionService.advanceActiveRuns();
    }
}

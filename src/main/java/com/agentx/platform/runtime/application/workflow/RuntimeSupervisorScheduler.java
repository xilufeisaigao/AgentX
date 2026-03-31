package com.agentx.platform.runtime.application.workflow;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeSupervisorScheduler {

    private final RuntimeSupervisorSweep runtimeSupervisorSweep;
    private final com.agentx.platform.runtime.support.RuntimeInfrastructureProperties runtimeProperties;

    public RuntimeSupervisorScheduler(
            RuntimeSupervisorSweep runtimeSupervisorSweep,
            com.agentx.platform.runtime.support.RuntimeInfrastructureProperties runtimeProperties
    ) {
        this.runtimeSupervisorSweep = runtimeSupervisorSweep;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${agentx.platform.runtime.supervisor-scan-interval:PT2S}').toMillis()}")
    public void sweep() {
        if (!runtimeProperties.isSupervisorEnabled()) {
            return;
        }
        runtimeSupervisorSweep.sweepOnce();
    }
}

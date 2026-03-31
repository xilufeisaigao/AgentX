package com.agentx.platform.runtime.application.workflow;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkflowDriverScheduler {

    private final WorkflowDriverService workflowDriverService;

    public WorkflowDriverScheduler(WorkflowDriverService workflowDriverService) {
        this.workflowDriverService = workflowDriverService;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${agentx.platform.runtime.driver-scan-interval:PT2S}').toMillis()}")
    public void drivePendingWorkflows() {
        if (!workflowDriverService.isDriverEnabled()) {
            return;
        }
        workflowDriverService.drivePendingWorkflows();
    }
}

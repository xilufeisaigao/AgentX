package com.agentx.agentxbackend.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WaitingWorkerTaskRefresher {

    private static final Logger log = LoggerFactory.getLogger(WaitingWorkerTaskRefresher.class);

    private final PlanningCommandService planningCommandService;
    private final boolean enabled;
    private final int maxTasksPerCycle;

    public WaitingWorkerTaskRefresher(
        PlanningCommandService planningCommandService,
        @Value("${agentx.planning.waiting-task-refresher.enabled:true}") boolean enabled,
        @Value("${agentx.planning.waiting-task-refresher.max-tasks-per-cycle:100}") int maxTasksPerCycle
    ) {
        this.planningCommandService = planningCommandService;
        this.enabled = enabled;
        this.maxTasksPerCycle = Math.max(1, maxTasksPerCycle);
    }

    @Scheduled(
        fixedDelayString = "${agentx.planning.waiting-task-refresher.poll-interval-ms:5000}",
        initialDelayString = "${agentx.planning.waiting-task-refresher.initial-delay-ms:5000}"
    )
    public void refreshWaitingTasks() {
        if (!enabled) {
            return;
        }
        int advanced = planningCommandService.refreshWaitingTasks(maxTasksPerCycle);
        if (advanced > 0) {
            log.info("Advanced WAITING_WORKER tasks to READY_FOR_ASSIGN, count={}", advanced);
        }
    }
}

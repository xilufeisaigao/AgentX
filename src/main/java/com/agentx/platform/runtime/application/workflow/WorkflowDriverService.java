package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.runtime.orchestration.langgraph.FixedCodingGraphFactory;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowDriverService {

    private final FlowStore flowStore;
    private final PlanningStore planningStore;
    private final ExecutionStore executionStore;
    private final FixedCodingGraphFactory graphFactory;
    private final TaskDispatcher taskDispatcher;
    private final CodingSessionService codingSessionService;
    private final RuntimeSupervisorSweep runtimeSupervisorSweep;
    private final RuntimeInfrastructureProperties runtimeProperties;

    public WorkflowDriverService(
            FlowStore flowStore,
            PlanningStore planningStore,
            ExecutionStore executionStore,
            FixedCodingGraphFactory graphFactory,
            TaskDispatcher taskDispatcher,
            CodingSessionService codingSessionService,
            RuntimeSupervisorSweep runtimeSupervisorSweep,
            RuntimeInfrastructureProperties runtimeProperties
    ) {
        this.flowStore = flowStore;
        this.planningStore = planningStore;
        this.executionStore = executionStore;
        this.graphFactory = graphFactory;
        this.taskDispatcher = taskDispatcher;
        this.codingSessionService = codingSessionService;
        this.runtimeSupervisorSweep = runtimeSupervisorSweep;
        this.runtimeProperties = runtimeProperties;
    }

    public void driveWorkflowOnce(String workflowRunId) {
        WorkflowRun workflowRun = flowStore.findRun(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("workflow run not found: " + workflowRunId));
        if (isStable(workflowRun.status())) {
            return;
        }
        if (!hasInFlightTaskRun(workflowRunId)) {
            graphFactory.compiledGraph().invoke(
                    Map.of("workflowRunId", workflowRunId),
                    RunnableConfig.builder().threadId(workflowRunId).build()
            );
        }
        advanceExecutionSlices();
    }

    public void drivePendingWorkflows() {
        runtimeSupervisorSweep.sweepOnce();
        for (WorkflowRun workflowRun : flowStore.listRunsByStatuses(List.of(
                WorkflowRunStatus.ACTIVE,
                WorkflowRunStatus.EXECUTING_TASKS,
                WorkflowRunStatus.VERIFYING
        ))) {
            if (hasInFlightTaskRun(workflowRun.workflowRunId())) {
                continue;
            }
            graphFactory.compiledGraph().invoke(
                    Map.of("workflowRunId", workflowRun.workflowRunId()),
                    RunnableConfig.builder().threadId(workflowRun.workflowRunId()).build()
            );
        }
        advanceExecutionSlices();
    }

    public boolean isDriverEnabled() {
        return runtimeProperties.isDriverEnabled();
    }

    private boolean isStable(WorkflowRunStatus status) {
        return status == WorkflowRunStatus.WAITING_ON_HUMAN
                || status == WorkflowRunStatus.COMPLETED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELED;
    }

    private void advanceExecutionSlices() {
        taskDispatcher.dispatchReadyTasks();
        codingSessionService.advanceActiveRuns();
        runtimeSupervisorSweep.sweepOnce();
    }

    private boolean hasInFlightTaskRun(String workflowRunId) {
        for (TaskRun run : executionStore.listActiveTaskRuns()) {
            if (planningStore.findWorkflowRunIdByTask(run.taskId())
                    .map(workflowRunId::equals)
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }
}

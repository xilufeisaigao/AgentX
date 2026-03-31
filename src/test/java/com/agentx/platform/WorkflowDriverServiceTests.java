package com.agentx.platform;

import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.flow.model.EntryMode;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.flow.model.WorkflowRunStatus;
import com.agentx.platform.domain.flow.port.FlowStore;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.application.workflow.CodingSessionService;
import com.agentx.platform.runtime.application.workflow.RuntimeSupervisorSweep;
import com.agentx.platform.runtime.application.workflow.TaskDispatcher;
import com.agentx.platform.runtime.application.workflow.WorkflowDriverService;
import com.agentx.platform.runtime.orchestration.langgraph.FixedCodingGraphFactory;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowDriverServiceTests {

    @Test
    void shouldSkipGraphInvokeWhenWorkflowAlreadyHasActiveTaskRun() {
        FlowStore flowStore = mock(FlowStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        FixedCodingGraphFactory graphFactory = mock(FixedCodingGraphFactory.class);
        @SuppressWarnings("unchecked")
        CompiledGraph<com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState> compiledGraph =
                mock(CompiledGraph.class);
        TaskDispatcher taskDispatcher = mock(TaskDispatcher.class);
        CodingSessionService codingSessionService = mock(CodingSessionService.class);
        RuntimeSupervisorSweep runtimeSupervisorSweep = mock(RuntimeSupervisorSweep.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();

        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(new WorkflowRun(
                "workflow-1",
                "builtin-coding-flow",
                "workflow",
                WorkflowRunStatus.EXECUTING_TASKS,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "tester")
        )));
        when(executionStore.listActiveTaskRuns()).thenReturn(List.of(activeRun("task-1", "ainst-1")));
        when(planningStore.findWorkflowRunIdByTask("task-1")).thenReturn(Optional.of("workflow-1"));
        when(graphFactory.compiledGraph()).thenReturn(compiledGraph);

        WorkflowDriverService service = new WorkflowDriverService(
                flowStore,
                planningStore,
                executionStore,
                graphFactory,
                taskDispatcher,
                codingSessionService,
                runtimeSupervisorSweep,
                properties
        );

        service.driveWorkflowOnce("workflow-1");

        verify(compiledGraph, never()).invoke(any(Map.class), any());
        verify(taskDispatcher).dispatchReadyTasks();
        verify(codingSessionService).advanceActiveRuns();
        verify(runtimeSupervisorSweep).sweepOnce();
    }

    @Test
    void shouldInvokeGraphWhenWorkflowHasNoActiveTaskRun() {
        FlowStore flowStore = mock(FlowStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        ExecutionStore executionStore = mock(ExecutionStore.class);
        FixedCodingGraphFactory graphFactory = mock(FixedCodingGraphFactory.class);
        @SuppressWarnings("unchecked")
        CompiledGraph<com.agentx.platform.runtime.orchestration.langgraph.PlatformWorkflowState> compiledGraph =
                mock(CompiledGraph.class);
        TaskDispatcher taskDispatcher = mock(TaskDispatcher.class);
        CodingSessionService codingSessionService = mock(CodingSessionService.class);
        RuntimeSupervisorSweep runtimeSupervisorSweep = mock(RuntimeSupervisorSweep.class);
        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();

        when(flowStore.findRun("workflow-1")).thenReturn(Optional.of(new WorkflowRun(
                "workflow-1",
                "builtin-coding-flow",
                "workflow",
                WorkflowRunStatus.EXECUTING_TASKS,
                EntryMode.MANUAL,
                false,
                new ActorRef(ActorType.HUMAN, "tester")
        )));
        when(executionStore.listActiveTaskRuns()).thenReturn(List.of());
        when(graphFactory.compiledGraph()).thenReturn(compiledGraph);

        WorkflowDriverService service = new WorkflowDriverService(
                flowStore,
                planningStore,
                executionStore,
                graphFactory,
                taskDispatcher,
                codingSessionService,
                runtimeSupervisorSweep,
                properties
        );

        service.driveWorkflowOnce("workflow-1");

        verify(compiledGraph).invoke(any(Map.class), any());
    }

    private TaskRun activeRun(String taskId, String agentInstanceId) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskRun(
                taskId + "-run-001",
                taskId,
                agentInstanceId,
                TaskRunStatus.RUNNING,
                RunKind.IMPL,
                taskId + "-snapshot-001",
                now.plusSeconds(20),
                now,
                now.minusSeconds(1),
                null,
                JsonPayload.emptyObject()
        );
    }
}

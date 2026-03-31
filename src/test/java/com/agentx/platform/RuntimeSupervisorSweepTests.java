package com.agentx.platform;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.intake.port.IntakeStore;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.planning.model.WorkTaskStatus;
import com.agentx.platform.domain.planning.port.PlanningStore;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.agentruntime.AgentRuntime;
import com.agentx.platform.runtime.agentruntime.ContainerObservation;
import com.agentx.platform.runtime.agentruntime.ContainerState;
import com.agentx.platform.runtime.application.workflow.RuntimeSupervisorSweep;
import com.agentx.platform.runtime.application.workflow.SupervisorRecoveryDecision;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSupervisorSweepTests {

    @Test
    void shouldRequeueFailedRunBelowRetryLimit() {
        ExecutionStore executionStore = mock(ExecutionStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setLeaseTtl(Duration.ofSeconds(20));
        properties.setMaxRunAttempts(2);

        RuntimeSupervisorSweep sweep = new RuntimeSupervisorSweep(
                executionStore,
                planningStore,
                intakeStore,
                workspaceProvisioner,
                agentRuntime,
                properties,
                new ObjectMapper()
        );

        TaskRun run = run("task-retry-run-001", "task-retry", "ainst-01", TaskRunStatus.RUNNING);
        AgentPoolInstance instance = instance("ainst-01", AgentPoolStatus.READY);
        WorkTask task = task("task-retry", WorkTaskStatus.IN_PROGRESS);
        GitWorkspace workspace = workspace(run.runId(), task.taskId());
        ContainerObservation observation = new ContainerObservation(
                ContainerState.EXITED,
                1,
                "boom",
                false,
                LocalDateTime.now().minusSeconds(3),
                LocalDateTime.now()
        );

        when(executionStore.listActiveTaskRuns()).thenReturn(List.of(run), List.of());
        when(executionStore.findAgentInstance(run.agentInstanceId())).thenReturn(Optional.of(instance));
        when(agentRuntime.observe(instance)).thenReturn(observation);
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(executionStore.findWorkspaceByRun(run.runId())).thenReturn(Optional.of(workspace));
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of(run));
        when(executionStore.listWorkspacesPendingCleanup()).thenReturn(List.of());
        when(executionStore.listActiveAgentInstances()).thenReturn(List.of(instance));
        when(workspaceProvisioner.cleanup(any(GitWorkspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<SupervisorRecoveryDecision> decisions = sweep.sweepOnce();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.runId()).isEqualTo(run.runId());
            assertThat(decision.action()).isEqualTo("RUN_REQUEUED");
        });

        verify(agentRuntime).terminate(instance);
        ArgumentCaptor<WorkTask> taskCaptor = ArgumentCaptor.forClass(WorkTask.class);
        verify(planningStore).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().status()).isEqualTo(WorkTaskStatus.READY);

        ArgumentCaptor<GitWorkspace> workspaceCaptor = ArgumentCaptor.forClass(GitWorkspace.class);
        verify(workspaceProvisioner).cleanup(workspaceCaptor.capture());
        assertThat(workspaceCaptor.getValue().status()).isEqualTo(GitWorkspaceStatus.FAILED);
        verify(intakeStore, never()).saveTicket(any(Ticket.class));
    }

    @Test
    void shouldBlockTaskAndCreateTicketAfterRetryBudgetIsExhausted() {
        ExecutionStore executionStore = mock(ExecutionStore.class);
        PlanningStore planningStore = mock(PlanningStore.class);
        IntakeStore intakeStore = mock(IntakeStore.class);
        WorkspaceProvisioner workspaceProvisioner = mock(WorkspaceProvisioner.class);
        AgentRuntime agentRuntime = mock(AgentRuntime.class);

        RuntimeInfrastructureProperties properties = new RuntimeInfrastructureProperties();
        properties.setLeaseTtl(Duration.ofSeconds(20));
        properties.setMaxRunAttempts(2);

        RuntimeSupervisorSweep sweep = new RuntimeSupervisorSweep(
                executionStore,
                planningStore,
                intakeStore,
                workspaceProvisioner,
                agentRuntime,
                properties,
                new ObjectMapper()
        );

        TaskRun previousRun = run("task-block-run-000", "task-block", "ainst-00", TaskRunStatus.FAILED);
        TaskRun activeRun = run("task-block-run-001", "task-block", "ainst-01", TaskRunStatus.RUNNING);
        AgentPoolInstance instance = instance("ainst-01", AgentPoolStatus.READY);
        WorkTask task = task("task-block", WorkTaskStatus.IN_PROGRESS);
        GitWorkspace workspace = workspace(activeRun.runId(), task.taskId());
        ContainerObservation observation = new ContainerObservation(
                ContainerState.MISSING,
                null,
                "container lost",
                true,
                LocalDateTime.now().minusSeconds(5),
                LocalDateTime.now()
        );

        when(executionStore.listActiveTaskRuns()).thenReturn(List.of(activeRun), List.of());
        when(executionStore.findAgentInstance(activeRun.agentInstanceId())).thenReturn(Optional.of(instance));
        when(agentRuntime.observe(instance)).thenReturn(observation);
        when(planningStore.findTask(task.taskId())).thenReturn(Optional.of(task));
        when(planningStore.findWorkflowRunIdByTask(task.taskId())).thenReturn(Optional.of("workflow-1"));
        when(executionStore.findWorkspaceByRun(activeRun.runId())).thenReturn(Optional.of(workspace));
        when(executionStore.listTaskRuns(task.taskId())).thenReturn(List.of(previousRun, activeRun));
        when(executionStore.listWorkspacesPendingCleanup()).thenReturn(List.of());
        when(executionStore.listActiveAgentInstances()).thenReturn(List.of(instance));
        when(workspaceProvisioner.cleanup(any(GitWorkspace.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<SupervisorRecoveryDecision> decisions = sweep.sweepOnce();

        assertThat(decisions).singleElement().satisfies(decision -> {
            assertThat(decision.runId()).isEqualTo(activeRun.runId());
            assertThat(decision.action()).isEqualTo("RUN_BLOCKED");
        });

        ArgumentCaptor<WorkTask> taskCaptor = ArgumentCaptor.forClass(WorkTask.class);
        verify(planningStore).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().status()).isEqualTo(WorkTaskStatus.BLOCKED);
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(intakeStore).saveTicket(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().taskId()).isEqualTo(task.taskId());
        verify(intakeStore).appendTicketEvent(any());
    }

    private TaskRun run(String runId, String taskId, String agentInstanceId, TaskRunStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new TaskRun(
                runId,
                taskId,
                agentInstanceId,
                status,
                RunKind.IMPL,
                taskId + "-snapshot-001",
                now.plusSeconds(20),
                now,
                now.minusSeconds(1),
                null,
                JsonPayload.emptyObject()
        );
    }

    private AgentPoolInstance instance(String instanceId, AgentPoolStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new AgentPoolInstance(
                instanceId,
                "coding-agent-java",
                "docker",
                status,
                "TASK_RUN_CONTAINER",
                "workflow-1",
                now.plusSeconds(20),
                now,
                "docker://" + instanceId,
                JsonPayload.emptyObject()
        );
    }

    private WorkTask task(String taskId, WorkTaskStatus status) {
        return new WorkTask(
                taskId,
                "module-api",
                "实现 healthz",
                "生成交付候选",
                "java-backend-task",
                status,
                List.of(new WriteScope("src/main/java")),
                null,
                new ActorRef(ActorType.AGENT, "architect-agent")
        );
    }

    private GitWorkspace workspace(String runId, String taskId) {
        return new GitWorkspace(
                "workspace-" + runId,
                runId,
                taskId,
                GitWorkspaceStatus.READY,
                "D:/repo",
                "D:/repo/.worktrees/" + runId,
                "task/" + taskId + "/001",
                "base-commit",
                "head-commit",
                null,
                CleanupStatus.PENDING
        );
    }
}

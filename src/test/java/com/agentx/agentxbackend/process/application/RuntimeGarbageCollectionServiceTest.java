package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateMaintenanceUseCase;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeGarbageCollectionServiceTest {

    @Mock
    private TaskQueryUseCase taskQueryUseCase;
    @Mock
    private TaskStateMutationUseCase taskStateMutationUseCase;
    @Mock
    private RunQueryUseCase runQueryUseCase;
    @Mock
    private MergeGateUseCase mergeGateUseCase;
    @Mock
    private MergeGateMaintenanceUseCase mergeGateMaintenanceUseCase;
    @Mock
    private DeliveredTaskMergeGateProcessManager deliveredTaskMergeGateProcessManager;

    @Test
    void collectOnceShouldReleaseAssignedTaskWhenActiveRunMissing() {
        RuntimeGarbageCollectionService service = newService();
        WorkTask assigned = workTask("TASK-1", TaskStatus.ASSIGNED, "RUN-404", Instant.now());

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of(assigned));
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of());
        when(runQueryUseCase.findRunById("RUN-404")).thenReturn(Optional.empty());

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(1, result.scannedAssignedTasks());
        assertEquals(1, result.releasedAssignments());
        assertEquals(0, result.promotedDeliveredAssignments());
        assertEquals(0, result.failedAssignmentReleases());
        verify(taskStateMutationUseCase).releaseAssignment("TASK-1");
        verifyNoInteractions(mergeGateUseCase);
    }

    @Test
    void collectOnceShouldKeepAssignedTaskWhenRunStillActive() {
        RuntimeGarbageCollectionService service = newService();
        WorkTask assigned = workTask("TASK-2", TaskStatus.ASSIGNED, "RUN-2", Instant.now());
        TaskRun runningRun = taskRun("RUN-2", "TASK-2", RunKind.IMPL, RunStatus.RUNNING);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of(assigned));
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of());
        when(runQueryUseCase.findRunById("RUN-2")).thenReturn(Optional.of(runningRun));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(0, result.releasedAssignments());
        assertEquals(0, result.promotedDeliveredAssignments());
        assertEquals(1, result.skippedHealthyAssignedTasks());
        verifyNoInteractions(taskStateMutationUseCase, mergeGateUseCase);
    }

    @Test
    void collectOnceShouldPromoteSucceededImplAssignmentIntoDelivered() {
        RuntimeGarbageCollectionService service = newService();
        WorkTask assigned = workTask("TASK-S", TaskStatus.ASSIGNED, "RUN-S", Instant.now());
        TaskRun succeededRun = taskRun("RUN-S", "TASK-S", RunKind.IMPL, RunStatus.SUCCEEDED);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of(assigned));
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of());
        when(runQueryUseCase.findRunById("RUN-S")).thenReturn(Optional.of(succeededRun));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(0, result.releasedAssignments());
        assertEquals(1, result.promotedDeliveredAssignments());
        verify(taskStateMutationUseCase).markDelivered("TASK-S");
        verify(deliveredTaskMergeGateProcessManager).onTaskDelivered("TASK-S");
    }

    @Test
    void collectOnceShouldCompleteSucceededVerifyAssignmentIntoDone() {
        RuntimeGarbageCollectionService service = newService();
        WorkTask assignedVerify = verifyTask("TASK-V", TaskStatus.ASSIGNED, "RUN-V", Instant.now());
        TaskRun succeededVerifyRun = taskRun("RUN-V", "TASK-V", RunKind.VERIFY, RunStatus.SUCCEEDED);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of(assignedVerify));
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of());
        when(runQueryUseCase.findRunById("RUN-V")).thenReturn(Optional.of(succeededVerifyRun));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(0, result.releasedAssignments());
        assertEquals(0, result.promotedDeliveredAssignments());
        assertEquals(1, result.completedVerifyAssignments());
        verify(taskStateMutationUseCase).markDone("TASK-V");
        verifyNoInteractions(deliveredTaskMergeGateProcessManager, mergeGateUseCase);
    }

    @Test
    void collectOnceShouldKickStaleDeliveredTaskWithoutVerifyRun() {
        RuntimeGarbageCollectionService service = newService();
        Instant staleTime = Instant.now().minusSeconds(300);
        WorkTask delivered = workTask("TASK-3", TaskStatus.DELIVERED, null, staleTime);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of());
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of(delivered));
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-3", RunKind.VERIFY)).thenReturn(false);
        when(runQueryUseCase.findLatestRunByTaskAndKind("TASK-3", RunKind.VERIFY)).thenReturn(Optional.empty());
        when(mergeGateUseCase.start("TASK-3"))
            .thenReturn(new MergeGateResult("TASK-3", "RUN-V-3", true, "ok"));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(1, result.scannedDeliveredTasks());
        assertEquals(1, result.staleDeliveredTasks());
        assertEquals(1, result.kickedMergeGates());
        verify(mergeGateUseCase).start("TASK-3");
    }

    @Test
    void collectOnceShouldSkipOnlyDeliveredTaskWhenVerifySucceededExists() {
        RuntimeGarbageCollectionService service = newService();
        Instant staleTime = Instant.now().minusSeconds(300);
        WorkTask deliveredWithActiveVerify = workTask("TASK-4", TaskStatus.DELIVERED, null, staleTime);
        WorkTask deliveredWithVerifyHistory = workTask("TASK-5", TaskStatus.DELIVERED, null, staleTime);
        TaskRun verifySucceeded = taskRun("RUN-V-5", "TASK-5", RunKind.VERIFY, RunStatus.SUCCEEDED);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of());
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100))
            .thenReturn(List.of(deliveredWithActiveVerify, deliveredWithVerifyHistory));
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-4", RunKind.VERIFY)).thenReturn(true);
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-5", RunKind.VERIFY)).thenReturn(false);
        when(runQueryUseCase.findLatestRunByTaskAndKind("TASK-5", RunKind.VERIFY)).thenReturn(Optional.of(verifySucceeded));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(2, result.staleDeliveredTasks());
        assertEquals(1, result.skippedDeliveredWithActiveVerify());
        assertEquals(1, result.skippedDeliveredWithVerifyHistory());
        assertEquals(0, result.kickedMergeGates());
        verifyNoInteractions(mergeGateUseCase);
    }

    @Test
    void collectOnceShouldRetryMergeGateWhenLatestVerifyFailed() {
        RuntimeGarbageCollectionService service = newService();
        Instant staleTime = Instant.now().minusSeconds(300);
        WorkTask delivered = workTask("TASK-VERIFY-FAILED", TaskStatus.DELIVERED, null, staleTime);
        TaskRun verifyFailed = taskRun("RUN-V-FAILED", "TASK-VERIFY-FAILED", RunKind.VERIFY, RunStatus.FAILED);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of());
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of(delivered));
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-VERIFY-FAILED", RunKind.VERIFY)).thenReturn(false);
        when(runQueryUseCase.findLatestRunByTaskAndKind("TASK-VERIFY-FAILED", RunKind.VERIFY))
            .thenReturn(Optional.of(verifyFailed));
        when(mergeGateUseCase.start("TASK-VERIFY-FAILED"))
            .thenReturn(new MergeGateResult("TASK-VERIFY-FAILED", "RUN-V-RETRY", true, "retry"));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(1, result.kickedMergeGates());
        verify(mergeGateUseCase).start("TASK-VERIFY-FAILED");
    }

    @Test
    void collectOnceShouldContinueWhenRepositoryRecoveryFails() {
        RuntimeGarbageCollectionService service = newService();
        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenThrow(new IllegalStateException("broken"));
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of());
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100)).thenReturn(List.of());

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertTrue(result.repositoryRecoveryFailures() > 0);
        assertEquals(0, result.kickedMergeGates());
    }

    @Test
    void collectOnceShouldContinueWhenSingleDeliveredTaskInspectionFails() {
        RuntimeGarbageCollectionService service = newService();
        Instant staleTime = Instant.now().minusSeconds(300);
        WorkTask brokenDelivered = workTask("TASK-ERR", TaskStatus.DELIVERED, null, staleTime);
        WorkTask healthyDelivered = workTask("TASK-OK", TaskStatus.DELIVERED, null, staleTime);

        when(mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded()).thenReturn(false);
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, 100)).thenReturn(List.of());
        when(taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, 100))
            .thenReturn(List.of(brokenDelivered, healthyDelivered));
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-ERR", RunKind.VERIFY))
            .thenThrow(new IllegalStateException("query failed"));
        when(runQueryUseCase.hasActiveRunByTaskAndKind("TASK-OK", RunKind.VERIFY)).thenReturn(false);
        when(runQueryUseCase.findLatestRunByTaskAndKind("TASK-OK", RunKind.VERIFY)).thenReturn(Optional.empty());
        when(mergeGateUseCase.start("TASK-OK")).thenReturn(new MergeGateResult("TASK-OK", "RUN-V-OK", true, "ok"));

        RuntimeGarbageCollectionService.CleanupResult result = service.collectOnce();

        assertEquals(2, result.staleDeliveredTasks());
        assertEquals(1, result.mergeGateFailures());
        assertEquals(1, result.kickedMergeGates());
        verify(mergeGateUseCase).start("TASK-OK");
        verify(deliveredTaskMergeGateProcessManager).handleMergeGateStartFailure(
            eq("TASK-ERR"),
            org.mockito.ArgumentMatchers.any(RuntimeException.class)
        );
    }

    private RuntimeGarbageCollectionService newService() {
        return new RuntimeGarbageCollectionService(
            taskQueryUseCase,
            taskStateMutationUseCase,
            runQueryUseCase,
            mergeGateUseCase,
            mergeGateMaintenanceUseCase,
            deliveredTaskMergeGateProcessManager,
            100,
            100,
            120,
            4
        );
    }

    private static WorkTask workTask(String taskId, TaskStatus status, String activeRunId, Instant updatedAt) {
        return new WorkTask(
            taskId,
            "MOD-1",
            "Task " + taskId,
            TaskTemplateId.fromValue("tmpl.impl.v0"),
            status,
            "[\"TP-JAVA-21\"]",
            activeRunId,
            "architect_agent",
            updatedAt.minusSeconds(60),
            updatedAt
        );
    }

    private static WorkTask verifyTask(String taskId, TaskStatus status, String activeRunId, Instant updatedAt) {
        return new WorkTask(
            taskId,
            "MOD-1",
            "Task " + taskId,
            TaskTemplateId.fromValue("tmpl.verify.v0"),
            status,
            "[\"TP-JAVA-21\"]",
            activeRunId,
            "architect_agent",
            updatedAt.minusSeconds(60),
            updatedAt
        );
    }

    private static TaskRun taskRun(String runId, String taskId, RunKind runKind, RunStatus status) {
        Instant now = Instant.now();
        return new TaskRun(
            runId,
            taskId,
            "WRK-1",
            status,
            runKind,
            "CTXS-1",
            now.plusSeconds(120),
            now,
            now.minusSeconds(30),
            status == RunStatus.RUNNING || status == RunStatus.WAITING_FOREMAN ? null : now,
            null,
            "[]",
            "BASE",
            "run/" + runId,
            "worktrees/" + taskId + "/" + runId,
            now.minusSeconds(30),
            now
        );
    }
}

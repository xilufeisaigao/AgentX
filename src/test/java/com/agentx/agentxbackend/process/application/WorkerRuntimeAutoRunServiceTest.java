package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.PreconditionFailedException;
import com.agentx.agentxbackend.execution.application.port.in.RunCommandUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.execution.domain.model.GitAlloc;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.TaskContext;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.process.application.port.out.WorkerTaskExecutorPort;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRuntimeAutoRunServiceTest {

    @Mock
    private WorkerCapabilityUseCase workerCapabilityUseCase;
    @Mock
    private RunCommandUseCase runCommandUseCase;
    @Mock
    private RunInternalUseCase runInternalUseCase;
    @Mock
    private WorkerTaskExecutorPort workerTaskExecutorPort;

    @Test
    void runOnceShouldFinishSucceededRun() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort
        );
        Worker worker = new Worker("WRK-1", WorkerStatus.READY, Instant.now(), Instant.now());
        TaskPackage taskPackage = buildTaskPackage("RUN-1", "TASK-1", RunKind.IMPL);

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-1")).thenReturn(Optional.empty());
        when(runCommandUseCase.claimTask("WRK-1")).thenReturn(Optional.of(taskPackage));
        when(workerTaskExecutorPort.execute(taskPackage)).thenReturn(
            WorkerTaskExecutorPort.ExecutionResult.succeeded("done", "abc123", null)
        );

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(1, result.claimedRuns());
        assertEquals(1, result.succeededRuns());
        assertEquals(0, result.failedRuns());
        verify(runCommandUseCase).finishRun(any(), any());
    }

    @Test
    void runOnceShouldAppendNeedInputEvent() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort
        );
        Worker worker = new Worker("WRK-2", WorkerStatus.READY, Instant.now(), Instant.now());
        TaskPackage taskPackage = buildTaskPackage("RUN-2", "TASK-2", RunKind.IMPL);

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-2")).thenReturn(Optional.empty());
        when(runCommandUseCase.claimTask("WRK-2")).thenReturn(Optional.of(taskPackage));
        when(workerTaskExecutorPort.execute(taskPackage)).thenReturn(
            WorkerTaskExecutorPort.ExecutionResult.needInput("NEED_CLARIFICATION", "need info", "{\"k\":\"v\"}")
        );

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(1, result.needInputRuns());
        verify(runCommandUseCase).appendEvent("RUN-2", "NEED_CLARIFICATION", "need info", "{\"k\":\"v\"}");
    }

    @Test
    void runOnceShouldFailRunWhenExecutorThrows() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort
        );
        Worker worker = new Worker("WRK-3", WorkerStatus.READY, Instant.now(), Instant.now());
        TaskPackage taskPackage = buildTaskPackage("RUN-3", "TASK-3", RunKind.IMPL);

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-3")).thenReturn(Optional.empty());
        when(runCommandUseCase.claimTask("WRK-3")).thenReturn(Optional.of(taskPackage));
        when(workerTaskExecutorPort.execute(taskPackage)).thenThrow(new IllegalStateException("boom"));

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(1, result.failedRuns());
        verify(runInternalUseCase).failRun("RUN-3", "Worker runtime exception: boom");
    }

    @Test
    void runOnceShouldSkipPreconditionFailedClaim() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort
        );
        Worker worker = new Worker("WRK-4", WorkerStatus.READY, Instant.now(), Instant.now());

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-4")).thenReturn(Optional.empty());
        when(runCommandUseCase.claimTask("WRK-4"))
            .thenThrow(new PreconditionFailedException("INIT gate is active."));

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(0, result.claimedRuns());
        assertEquals(0, result.succeededRuns());
        assertEquals(0, result.needInputRuns());
        assertEquals(0, result.failedRuns());
        verifyNoInteractions(workerTaskExecutorPort, runInternalUseCase);
    }

    @Test
    void runOnceShouldPrioritizeRunningVerifyRunBeforeClaimingNewTask() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort
        );
        Worker worker = new Worker("WRK-VERIFY", WorkerStatus.READY, Instant.now(), Instant.now());
        TaskPackage verifyPackage = buildTaskPackage("RUN-V-1", "TASK-V-1", RunKind.VERIFY);

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-VERIFY")).thenReturn(Optional.of(verifyPackage));
        when(workerTaskExecutorPort.execute(verifyPackage)).thenReturn(
            WorkerTaskExecutorPort.ExecutionResult.succeeded("verify done", null, null)
        );

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(1, result.claimedRuns());
        assertEquals(1, result.succeededRuns());
        verify(runCommandUseCase, never()).claimTask("WRK-VERIFY");
        verify(runCommandUseCase).finishRun(any(), any());
    }

    @Test
    void runOnceShouldHeartbeatWhileWorkerExecutionIsStillRunning() {
        WorkerRuntimeAutoRunService service = new WorkerRuntimeAutoRunService(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort,
            1,
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "test-run-heartbeat");
                thread.setDaemon(true);
                return thread;
            })
        );
        Worker worker = new Worker("WRK-HB", WorkerStatus.READY, Instant.now(), Instant.now());
        TaskPackage taskPackage = buildTaskPackage("RUN-HB-1", "TASK-HB-1", RunKind.VERIFY);
        CountDownLatch heartbeatLatch = new CountDownLatch(1);

        when(workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, 8)).thenReturn(List.of(worker));
        when(runCommandUseCase.pickupRunningVerifyRun("WRK-HB")).thenReturn(Optional.of(taskPackage));
        doAnswer(invocation -> {
            heartbeatLatch.countDown();
            return null;
        }).when(runCommandUseCase).heartbeat("RUN-HB-1");
        when(workerTaskExecutorPort.execute(taskPackage)).thenAnswer(invocation -> {
            assertTrue(heartbeatLatch.await(3, TimeUnit.SECONDS));
            return WorkerTaskExecutorPort.ExecutionResult.succeeded("verify done", null, null);
        });

        WorkerRuntimeAutoRunService.AutoRunResult result = service.runOnce(8);

        assertEquals(1, result.succeededRuns());
        verify(runCommandUseCase, atLeastOnce()).heartbeat("RUN-HB-1");
    }

    private static TaskPackage buildTaskPackage(String runId, String taskId, RunKind runKind) {
        return new TaskPackage(
            runId,
            taskId,
            "Auto-run task " + taskId,
            "MOD-1",
            "CTXS-1",
            runKind,
            runKind == RunKind.VERIFY ? "tmpl.verify.v0" : "tmpl.impl.v0",
            List.of("TP-GIT-2"),
            "file:.agentx/context/task-skills/mock.md",
            new TaskContext("task:" + taskId, List.of(), List.of(), "git:BASE"),
            List.of("./"),
            runKind == RunKind.VERIFY ? List.of() : List.of("./"),
            runKind == RunKind.VERIFY ? List.of("echo verify") : List.of(),
            List.of("Need clarification if missing facts."),
            List.of("work_report"),
            new GitAlloc("BASE", "run/" + runId, "worktrees/" + taskId + "/" + runId)
        );
    }
}

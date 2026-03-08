package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.contextpack.application.port.in.ContextCompileUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
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
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifyFailureRecoveryProcessManagerTest {

    @Mock
    private RunQueryUseCase runQueryUseCase;
    @Mock
    private MergeGateUseCase mergeGateUseCase;
    @Mock
    private TaskQueryUseCase taskQueryUseCase;
    @Mock
    private TaskStateMutationUseCase taskStateMutationUseCase;
    @Mock
    private ContextCompileUseCase contextCompileUseCase;

    @Test
    void shouldRetryVerifyWhenFailureLooksLikeInfrastructureAndAttemptsAllow() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-1",
            "TASK-1",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "VERIFY command failed: mvn -q test, reason=timeout", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-1")).thenReturn(Optional.of(deliveredTask("TASK-1")));
        when(runQueryUseCase.countVerifyRunsByTaskAndBaseCommit("TASK-1", "abc123")).thenReturn(1);
        when(mergeGateUseCase.start("TASK-1"))
            .thenReturn(new MergeGateResult("TASK-1", "RUN-V-RETRY-1", true, "retry"));

        manager.onVerifyFailed(event);

        verify(mergeGateUseCase).start("TASK-1");
        verify(taskStateMutationUseCase, never()).reopenDelivered("TASK-1");
        verifyNoInteractions(contextCompileUseCase);
    }

    @Test
    void shouldRetryVerifyWhenWorktreeDirtyLooksLikeRuntimePolicyFailure() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-DIRTY-1",
            "TASK-DIRTY-1",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload(
                "FAILED",
                "VERIFY run must be read-only, but worktree became dirty: target/",
                null,
                null
            )
        );
        when(taskQueryUseCase.findTaskById("TASK-DIRTY-1")).thenReturn(Optional.of(deliveredTask("TASK-DIRTY-1")));
        when(runQueryUseCase.countVerifyRunsByTaskAndBaseCommit("TASK-DIRTY-1", "abc123")).thenReturn(1);
        when(mergeGateUseCase.start("TASK-DIRTY-1"))
            .thenReturn(new MergeGateResult("TASK-DIRTY-1", "RUN-V-DIRTY-RETRY-1", true, "retry"));

        manager.onVerifyFailed(event);

        verify(mergeGateUseCase).start("TASK-DIRTY-1");
        verify(taskStateMutationUseCase, never()).reopenDelivered("TASK-DIRTY-1");
        verifyNoInteractions(contextCompileUseCase);
    }

    @Test
    void shouldReopenTaskWhenInfrastructureFailureExceededRetryLimit() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-2",
            "TASK-2",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "Worker runtime exception: docker timeout", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-2")).thenReturn(Optional.of(deliveredTask("TASK-2")));
        when(runQueryUseCase.countVerifyRunsByTaskAndBaseCommit("TASK-2", "abc123")).thenReturn(3);

        manager.onVerifyFailed(event);

        verify(mergeGateUseCase, never()).start("TASK-2");
        verify(taskStateMutationUseCase).reopenDelivered("TASK-2");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-2", "IMPL", "VERIFY_FAILED_REOPEN");
    }

    @Test
    void shouldReopenTaskWhenFailureIsNotInfrastructure() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-3",
            "TASK-3",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "VERIFY command failed: mvn -q test, output=tests failed", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-3")).thenReturn(Optional.of(deliveredTask("TASK-3")));

        manager.onVerifyFailed(event);

        verifyNoInteractions(runQueryUseCase);
        verify(mergeGateUseCase, never()).start("TASK-3");
        verify(taskStateMutationUseCase).reopenDelivered("TASK-3");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-3", "IMPL", "VERIFY_FAILED_REOPEN");
    }

    @Test
    void shouldReopenTaskWhenVerifyReasonContainsBuildFailure() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-3B",
            "TASK-3B",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload(
                "FAILED",
                "VERIFY command failed: mvn -q -DskipTests validate, reason='dependencies.dependency.version' for mysql:mysql-connector-java:jar is missing",
                null,
                null
            )
        );
        when(taskQueryUseCase.findTaskById("TASK-3B")).thenReturn(Optional.of(deliveredTask("TASK-3B")));

        manager.onVerifyFailed(event);

        verifyNoInteractions(runQueryUseCase);
        verify(mergeGateUseCase, never()).start("TASK-3B");
        verify(taskStateMutationUseCase).reopenDelivered("TASK-3B");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-3B", "IMPL", "VERIFY_FAILED_REOPEN");
    }

    @Test
    void shouldReopenTaskWhenVerifyCommandsAreMissing() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-3C",
            "TASK-3C",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "VERIFY run requires non-empty verify_commands", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-3C")).thenReturn(Optional.of(deliveredTask("TASK-3C")));

        manager.onVerifyFailed(event);

        verifyNoInteractions(runQueryUseCase);
        verify(mergeGateUseCase, never()).start("TASK-3C");
        verify(taskStateMutationUseCase).reopenDelivered("TASK-3C");
        verify(contextCompileUseCase).compileTaskContextPack("TASK-3C", "IMPL", "VERIFY_FAILED_REOPEN");
    }

    @Test
    void shouldIgnoreNonVerifyOrNonFailedEvents() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent verifySucceeded = new RunFinishedEvent(
            "RUN-V-4",
            "TASK-4",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("SUCCEEDED", "ok", null, null)
        );
        RunFinishedEvent implFailed = new RunFinishedEvent(
            "RUN-I-1",
            "TASK-5",
            RunKind.IMPL,
            "abc123",
            new RunFinishedPayload("FAILED", "failed", null, null)
        );

        manager.onVerifyFailed(verifySucceeded);
        manager.onVerifyFailed(implFailed);

        verifyNoInteractions(runQueryUseCase, mergeGateUseCase, taskQueryUseCase, taskStateMutationUseCase, contextCompileUseCase);
    }

    @Test
    void shouldIgnorePlannedVerifyTaskFailure() {
        VerifyFailureRecoveryProcessManager manager = new VerifyFailureRecoveryProcessManager(
            runQueryUseCase,
            mergeGateUseCase,
            taskQueryUseCase,
            taskStateMutationUseCase,
            contextCompileUseCase,
            2
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-V-PLANNED",
            "TASK-PLANNED",
            RunKind.VERIFY,
            "abc123",
            new RunFinishedPayload("FAILED", "VERIFY command failed: mvn -q test, reason=tests failed", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-PLANNED")).thenReturn(Optional.of(assignedVerifyTask("TASK-PLANNED")));

        manager.onVerifyFailed(event);

        verifyNoInteractions(runQueryUseCase, mergeGateUseCase, taskStateMutationUseCase, contextCompileUseCase);
    }

    private static WorkTask deliveredTask(String taskId) {
        return new WorkTask(
            taskId,
            "MOD-1",
            "impl",
            TaskTemplateId.TMPL_IMPL_V0,
            TaskStatus.DELIVERED,
            "[\"TP-JAVA-21\"]",
            null,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
    }

    private static WorkTask assignedVerifyTask(String taskId) {
        return new WorkTask(
            taskId,
            "MOD-1",
            "verify",
            TaskTemplateId.TMPL_VERIFY_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-VERIFY",
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
    }
}

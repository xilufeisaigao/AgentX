package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.MergeGateReplanRequiredException;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MergeGateCompletionProcessManagerTest {

    @Mock
    private MergeGateCompletionUseCase mergeGateCompletionUseCase;
    @Mock
    private MergeGateUseCase mergeGateUseCase;
    @Mock
    private TaskQueryUseCase taskQueryUseCase;

    @Test
    void onVerifySucceededShouldMarkDone() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
            mergeGateUseCase,
            taskQueryUseCase
        );
        when(taskQueryUseCase.findTaskById("TASK-1")).thenReturn(Optional.of(deliveredTask("TASK-1")));

        manager.onVerifySucceeded("TASK-1", "RUN-VERIFY-1", "abc123");

        verify(mergeGateCompletionUseCase).completeVerifySuccess("TASK-1", "RUN-VERIFY-1", "abc123");
    }

    @Test
    void onVerifySucceededShouldRejectBlankTaskId() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
            mergeGateUseCase,
            taskQueryUseCase
        );

        assertThrows(IllegalArgumentException.class, () -> manager.onVerifySucceeded(" ", "RUN-1", "abc"));
    }

    @Test
    void onVerifySucceededShouldIgnorePlannedVerifyTask() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
            mergeGateUseCase,
            taskQueryUseCase
        );
        when(taskQueryUseCase.findTaskById("TASK-VERIFY")).thenReturn(Optional.of(assignedVerifyTask("TASK-VERIFY")));

        manager.onVerifySucceeded("TASK-VERIFY", "RUN-VERIFY", "abc123");

        verifyNoInteractions(mergeGateCompletionUseCase);
    }

    @Test
    void onVerifySucceededShouldRestartMergeGateWhenVerifyResultBecameStale() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
            mergeGateUseCase,
            taskQueryUseCase
        );
        when(taskQueryUseCase.findTaskById("TASK-RESTART")).thenReturn(Optional.of(deliveredTask("TASK-RESTART")));
        org.mockito.Mockito.doThrow(
            new MergeGateReplanRequiredException("stale verify", new IllegalStateException("ff-only failed"))
        ).when(mergeGateCompletionUseCase).completeVerifySuccess("TASK-RESTART", "RUN-STale", "abc123");
        when(mergeGateUseCase.start("TASK-RESTART"))
            .thenReturn(new MergeGateResult("TASK-RESTART", "RUN-RETRY", true, "retry started"));

        manager.onVerifySucceeded("TASK-RESTART", "RUN-STale", "abc123");

        verify(mergeGateUseCase).start("TASK-RESTART");
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

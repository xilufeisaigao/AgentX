package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
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
    private TaskQueryUseCase taskQueryUseCase;

    @Test
    void onVerifySucceededShouldMarkDone() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
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
            taskQueryUseCase
        );

        assertThrows(IllegalArgumentException.class, () -> manager.onVerifySucceeded(" ", "RUN-1", "abc"));
    }

    @Test
    void onVerifySucceededShouldIgnorePlannedVerifyTask() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(
            mergeGateCompletionUseCase,
            taskQueryUseCase
        );
        when(taskQueryUseCase.findTaskById("TASK-VERIFY")).thenReturn(Optional.of(assignedVerifyTask("TASK-VERIFY")));

        manager.onVerifySucceeded("TASK-VERIFY", "RUN-VERIFY", "abc123");

        verifyNoInteractions(mergeGateCompletionUseCase);
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

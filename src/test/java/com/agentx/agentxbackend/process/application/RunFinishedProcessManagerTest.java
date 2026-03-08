package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunFinishedProcessManagerTest {

    @Mock
    private TaskStateMutationUseCase taskStateMutationUseCase;
    @Mock
    private TaskQueryUseCase taskQueryUseCase;

    @Test
    void handleShouldMarkDeliveredWhenImplSucceeded() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase, taskQueryUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-1",
            "TASK-1",
            RunKind.IMPL,
            new RunFinishedPayload("SUCCEEDED", "ok", "c1", null)
        );

        manager.handle(event);

        verify(taskStateMutationUseCase).markDelivered("TASK-1");
        verify(taskStateMutationUseCase, never()).releaseAssignment("TASK-1");
    }

    @Test
    void handleShouldReleaseAssignmentWhenImplFailed() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase, taskQueryUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-2",
            "TASK-2",
            RunKind.IMPL,
            new RunFinishedPayload("FAILED", "oops", null, null)
        );

        manager.handle(event);

        verify(taskStateMutationUseCase).releaseAssignment("TASK-2");
        verify(taskStateMutationUseCase, never()).markDelivered("TASK-2");
    }

    @Test
    void handleShouldCompleteAssignedVerifyTaskWhenVerifySucceeded() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase, taskQueryUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-3",
            "TASK-3",
            RunKind.VERIFY,
            new RunFinishedPayload("SUCCEEDED", "verify ok", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-3")).thenReturn(Optional.of(assignedVerifyTask("TASK-3")));

        manager.handle(event);

        verify(taskStateMutationUseCase).markDone("TASK-3");
        verify(taskStateMutationUseCase, never()).releaseAssignment("TASK-3");
    }

    @Test
    void handleShouldReleaseAssignedVerifyTaskWhenVerifyFailed() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase, taskQueryUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-4",
            "TASK-4",
            RunKind.VERIFY,
            new RunFinishedPayload("FAILED", "verify failed", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-4")).thenReturn(Optional.of(assignedVerifyTask("TASK-4")));

        manager.handle(event);

        verify(taskStateMutationUseCase).releaseAssignment("TASK-4");
        verify(taskStateMutationUseCase, never()).markDone("TASK-4");
    }

    @Test
    void handleShouldIgnoreMergeGateVerifyRun() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase, taskQueryUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-5",
            "TASK-5",
            RunKind.VERIFY,
            new RunFinishedPayload("SUCCEEDED", "verify ok", null, null)
        );
        when(taskQueryUseCase.findTaskById("TASK-5")).thenReturn(Optional.of(deliveredImplTask("TASK-5")));

        manager.handle(event);

        verify(taskStateMutationUseCase, never()).markDelivered("TASK-5");
        verify(taskStateMutationUseCase, never()).releaseAssignment("TASK-5");
        verify(taskStateMutationUseCase, never()).markDone("TASK-5");
    }

    private static WorkTask assignedVerifyTask(String taskId) {
        return new WorkTask(
            taskId,
            "MOD-1",
            "verify",
            TaskTemplateId.TMPL_VERIFY_V0,
            TaskStatus.ASSIGNED,
            "[\"TP-JAVA-21\"]",
            "RUN-" + taskId,
            "architect_agent",
            Instant.parse("2026-02-22T00:00:00Z"),
            Instant.parse("2026-02-22T00:00:00Z")
        );
    }

    private static WorkTask deliveredImplTask(String taskId) {
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
}

package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RunFinishedProcessManagerTest {

    @Mock
    private TaskStateMutationUseCase taskStateMutationUseCase;

    @Test
    void handleShouldMarkDeliveredWhenImplSucceeded() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase);
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
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase);
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
    void handleShouldIgnoreVerifyRun() {
        RunFinishedProcessManager manager = new RunFinishedProcessManager(taskStateMutationUseCase);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-3",
            "TASK-3",
            RunKind.VERIFY,
            new RunFinishedPayload("SUCCEEDED", "verify ok", null, null)
        );

        manager.handle(event);

        verify(taskStateMutationUseCase, never()).markDelivered("TASK-3");
        verify(taskStateMutationUseCase, never()).releaseAssignment("TASK-3");
    }
}


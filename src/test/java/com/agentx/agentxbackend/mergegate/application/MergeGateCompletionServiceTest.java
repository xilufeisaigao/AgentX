package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MergeGateCompletionServiceTest {

    @Mock
    private TaskStateMutationPort taskStateMutationPort;
    @Mock
    private GitClientPort gitClientPort;
    @Mock
    private IntegrationLaneLockPort integrationLaneLockPort;

    @Test
    void completeVerifySuccessShouldFastForwardThenMarkDone() {
        MergeGateCompletionService service = new MergeGateCompletionService(
            taskStateMutationPort,
            gitClientPort,
            integrationLaneLockPort
        );
        when(integrationLaneLockPort.tryAcquire("integration-lane")).thenReturn(true);
        when(taskStateMutationPort.resolveSessionIdByTaskId("TASK-1")).thenReturn("SES-1");

        service.completeVerifySuccess("TASK-1", "RUN-VERIFY-1", "abc123");

        InOrder inOrder = inOrder(integrationLaneLockPort, gitClientPort, taskStateMutationPort);
        inOrder.verify(integrationLaneLockPort).tryAcquire("integration-lane");
        inOrder.verify(gitClientPort).fastForwardMain("SES-1", "abc123");
        inOrder.verify(gitClientPort).ensureDeliveryTagOnMain("SES-1", "abc123");
        inOrder.verify(taskStateMutationPort).markDone("TASK-1");
        inOrder.verify(integrationLaneLockPort).release("integration-lane");
    }

    @Test
    void completeVerifySuccessShouldRequestReplanWhenMainMovedAfterVerify() {
        MergeGateCompletionService service = new MergeGateCompletionService(
            taskStateMutationPort,
            gitClientPort,
            integrationLaneLockPort
        );
        when(integrationLaneLockPort.tryAcquire("integration-lane")).thenReturn(true);
        when(taskStateMutationPort.resolveSessionIdByTaskId("TASK-REPLAN")).thenReturn("SES-REPLAN");
        doThrow(
            new IllegalStateException("Git command failed (exit 128): git merge --ff-only abc999, output=fatal: Not possible to fast-forward, aborting.")
        ).when(gitClientPort).fastForwardMain("SES-REPLAN", "abc999");

        MergeGateReplanRequiredException ex = assertThrows(
            MergeGateReplanRequiredException.class,
            () -> service.completeVerifySuccess("TASK-REPLAN", "RUN-VERIFY-REPLAN", "abc999")
        );

        assertTrue(ex.getMessage().contains("RUN-VERIFY-REPLAN"));
        verify(gitClientPort, never()).ensureDeliveryTagOnMain("SES-REPLAN", "abc999");
        verify(taskStateMutationPort, never()).markDone("TASK-REPLAN");
        verify(integrationLaneLockPort).release("integration-lane");
    }

    @Test
    void completeVerifySuccessShouldFailWhenLaneBusy() {
        MergeGateCompletionService service = new MergeGateCompletionService(
            taskStateMutationPort,
            gitClientPort,
            integrationLaneLockPort
        );
        when(integrationLaneLockPort.tryAcquire("integration-lane")).thenReturn(false);

        assertThrows(
            IllegalStateException.class,
            () -> service.completeVerifySuccess("TASK-2", "RUN-VERIFY-2", "def456")
        );

        verify(gitClientPort, never()).fastForwardMain("SES-2", "def456");
        verify(gitClientPort, never()).ensureDeliveryTagOnMain("SES-2", "def456");
        verify(taskStateMutationPort, never()).markDone("TASK-2");
    }
}


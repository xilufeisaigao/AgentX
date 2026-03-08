package com.agentx.agentxbackend.mergegate.application;

import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import com.agentx.agentxbackend.mergegate.application.port.out.IntegrationLaneLockPort;
import com.agentx.agentxbackend.mergegate.application.port.out.RunCreationPort;
import com.agentx.agentxbackend.mergegate.application.port.out.TaskStateMutationPort;
import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MergeGateServiceTest {

    @Mock
    private TaskStateMutationPort taskStateMutationPort;
    @Mock
    private RunCreationPort runCreationPort;
    @Mock
    private GitClientPort gitClientPort;
    @Mock
    private IntegrationLaneLockPort integrationLaneLockPort;

    @Test
    void startShouldCreateVerifyRunWhenLockAcquired() {
        MergeGateService service = new MergeGateService(
            taskStateMutationPort,
            runCreationPort,
            gitClientPort,
            integrationLaneLockPort
        );
        when(integrationLaneLockPort.tryAcquire("integration-lane")).thenReturn(true);
        when(taskStateMutationPort.resolveSessionIdByTaskId("TASK-1")).thenReturn("SES-1");
        when(gitClientPort.readMainHead("SES-1")).thenReturn("main-head-1");
        when(gitClientPort.rebaseTaskBranch("SES-1", "TASK-1", "main-head-1"))
            .thenReturn(new MergeCandidate("TASK-1", "main-head-1", "merge-candidate-1", "refs/agentx/candidate/task-1/1"));
        when(runCreationPort.createVerifyRun("TASK-1", "merge-candidate-1")).thenReturn("RUN-VERIFY-1");

        MergeGateResult result = service.start("TASK-1");

        assertTrue(result.accepted());
        assertEquals("TASK-1", result.taskId());
        assertEquals("RUN-VERIFY-1", result.verifyRunId());
        InOrder inOrder = inOrder(integrationLaneLockPort, gitClientPort, runCreationPort);
        inOrder.verify(integrationLaneLockPort).tryAcquire("integration-lane");
        inOrder.verify(gitClientPort).readMainHead("SES-1");
        inOrder.verify(gitClientPort).rebaseTaskBranch("SES-1", "TASK-1", "main-head-1");
        inOrder.verify(runCreationPort).createVerifyRun("TASK-1", "merge-candidate-1");
        inOrder.verify(integrationLaneLockPort).release("integration-lane");
    }

    @Test
    void startShouldRejectWhenIntegrationLaneBusy() {
        MergeGateService service = new MergeGateService(
            taskStateMutationPort,
            runCreationPort,
            gitClientPort,
            integrationLaneLockPort
        );
        when(integrationLaneLockPort.tryAcquire("integration-lane")).thenReturn(false);

        MergeGateResult result = service.start("TASK-2");

        assertFalse(result.accepted());
        assertEquals("TASK-2", result.taskId());
        verify(gitClientPort, never()).readMainHead("SES-1");
        verify(runCreationPort, never()).createVerifyRun("TASK-2", "anything");
    }
}


package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateCompletionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MergeGateCompletionProcessManagerTest {

    @Mock
    private MergeGateCompletionUseCase mergeGateCompletionUseCase;

    @Test
    void onVerifySucceededShouldMarkDone() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(mergeGateCompletionUseCase);

        manager.onVerifySucceeded("TASK-1", "RUN-VERIFY-1", "abc123");

        verify(mergeGateCompletionUseCase).completeVerifySuccess("TASK-1", "RUN-VERIFY-1", "abc123");
    }

    @Test
    void onVerifySucceededShouldRejectBlankTaskId() {
        MergeGateCompletionProcessManager manager = new MergeGateCompletionProcessManager(mergeGateCompletionUseCase);

        assertThrows(IllegalArgumentException.class, () -> manager.onVerifySucceeded(" ", "RUN-1", "abc"));
    }
}

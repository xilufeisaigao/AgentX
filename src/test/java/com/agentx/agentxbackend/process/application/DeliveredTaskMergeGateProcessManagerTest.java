package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveredTaskMergeGateProcessManagerTest {

    @Mock
    private MergeGateUseCase mergeGateUseCase;

    @Mock
    private MergeConflictRecoveryProcessManager mergeConflictRecoveryProcessManager;

    @Test
    void onTaskDeliveredShouldStartMergeGate() {
        DeliveredTaskMergeGateProcessManager manager = new DeliveredTaskMergeGateProcessManager(
            mergeGateUseCase,
            mergeConflictRecoveryProcessManager
        );
        when(mergeGateUseCase.start("TASK-1"))
            .thenReturn(new MergeGateResult("TASK-1", "RUN-V-1", true, "ok"));

        manager.onTaskDelivered("TASK-1");

        verify(mergeGateUseCase).start("TASK-1");
        verify(mergeConflictRecoveryProcessManager, never()).onMergeConflict(anyString(), anyString());
    }

    @Test
    void onTaskDeliveredShouldCreateConflictRecoveryWhenRebaseConflictHappens() {
        DeliveredTaskMergeGateProcessManager manager = new DeliveredTaskMergeGateProcessManager(
            mergeGateUseCase,
            mergeConflictRecoveryProcessManager
        );
        when(mergeGateUseCase.start("TASK-2"))
            .thenThrow(new IllegalStateException("Git rebase failed for task/TASK-2"));

        manager.onTaskDelivered("TASK-2");

        verify(mergeGateUseCase).start("TASK-2");
        verify(mergeConflictRecoveryProcessManager).onMergeConflict("TASK-2", "Git rebase failed for task/TASK-2");
    }
}

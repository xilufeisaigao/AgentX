package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.process.application.MergeGateCompletionProcessManager;
import com.agentx.agentxbackend.process.application.RunFinishedProcessManager;
import com.agentx.agentxbackend.process.application.RunNeedsInputProcessManager;
import com.agentx.agentxbackend.process.application.VerifyFailureRecoveryProcessManager;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RunDomainEventListenerTest {

    @Test
    void shouldDelegateNeedDecision() {
        RunNeedsInputProcessManager needsManager = mock(RunNeedsInputProcessManager.class);
        RunFinishedProcessManager finishedManager = mock(RunFinishedProcessManager.class);
        MergeGateCompletionProcessManager completionManager = mock(MergeGateCompletionProcessManager.class);
        VerifyFailureRecoveryProcessManager recoveryManager = mock(VerifyFailureRecoveryProcessManager.class);
        RunDomainEventListener listener = new RunDomainEventListener(
            needsManager,
            finishedManager,
            completionManager,
            recoveryManager
        );
        RunNeedsDecisionEvent event = new RunNeedsDecisionEvent("RUN-1", "TASK-1", "need decision", null);

        listener.onRunNeedsDecision(event);

        verify(needsManager).handle(event);
    }

    @Test
    void shouldDelegateNeedClarification() {
        RunNeedsInputProcessManager needsManager = mock(RunNeedsInputProcessManager.class);
        RunFinishedProcessManager finishedManager = mock(RunFinishedProcessManager.class);
        MergeGateCompletionProcessManager completionManager = mock(MergeGateCompletionProcessManager.class);
        VerifyFailureRecoveryProcessManager recoveryManager = mock(VerifyFailureRecoveryProcessManager.class);
        RunDomainEventListener listener = new RunDomainEventListener(
            needsManager,
            finishedManager,
            completionManager,
            recoveryManager
        );
        RunNeedsClarificationEvent event = new RunNeedsClarificationEvent("RUN-2", "TASK-2", "need detail", null);

        listener.onRunNeedsClarification(event);

        verify(needsManager).handle(event);
    }

    @Test
    void shouldMarkDoneWhenVerifyRunSucceeded() {
        RunNeedsInputProcessManager needsManager = mock(RunNeedsInputProcessManager.class);
        RunFinishedProcessManager finishedManager = mock(RunFinishedProcessManager.class);
        MergeGateCompletionProcessManager completionManager = mock(MergeGateCompletionProcessManager.class);
        VerifyFailureRecoveryProcessManager recoveryManager = mock(VerifyFailureRecoveryProcessManager.class);
        RunDomainEventListener listener = new RunDomainEventListener(
            needsManager,
            finishedManager,
            completionManager,
            recoveryManager
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-3",
            "TASK-3",
            RunKind.VERIFY,
            "merge-candidate-3",
            new RunFinishedPayload("SUCCEEDED", "ok", null, null)
        );

        listener.onRunFinished(event);

        verify(finishedManager).handle(event);
        verify(completionManager).onVerifySucceeded("TASK-3", "RUN-3", "merge-candidate-3");
        verifyNoInteractions(recoveryManager);
    }

    @Test
    void shouldNotMarkDoneWhenVerifyRunFailed() {
        RunNeedsInputProcessManager needsManager = mock(RunNeedsInputProcessManager.class);
        RunFinishedProcessManager finishedManager = mock(RunFinishedProcessManager.class);
        MergeGateCompletionProcessManager completionManager = mock(MergeGateCompletionProcessManager.class);
        VerifyFailureRecoveryProcessManager recoveryManager = mock(VerifyFailureRecoveryProcessManager.class);
        RunDomainEventListener listener = new RunDomainEventListener(
            needsManager,
            finishedManager,
            completionManager,
            recoveryManager
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-4",
            "TASK-4",
            RunKind.VERIFY,
            "merge-candidate-4",
            new RunFinishedPayload("FAILED", "bad", null, null)
        );

        listener.onRunFinished(event);

        verify(finishedManager).handle(event);
        verify(recoveryManager).onVerifyFailed(event);
        verifyNoInteractions(completionManager);
    }

    @Test
    void shouldNotMarkDoneWhenVerifyRunSucceededButMergeCandidateMissing() {
        RunNeedsInputProcessManager needsManager = mock(RunNeedsInputProcessManager.class);
        RunFinishedProcessManager finishedManager = mock(RunFinishedProcessManager.class);
        MergeGateCompletionProcessManager completionManager = mock(MergeGateCompletionProcessManager.class);
        VerifyFailureRecoveryProcessManager recoveryManager = mock(VerifyFailureRecoveryProcessManager.class);
        RunDomainEventListener listener = new RunDomainEventListener(
            needsManager,
            finishedManager,
            completionManager,
            recoveryManager
        );
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-5",
            "TASK-5",
            RunKind.VERIFY,
            " ",
            new RunFinishedPayload("SUCCEEDED", "ok", null, null)
        );

        listener.onRunFinished(event);

        verify(finishedManager).handle(event);
        verifyNoInteractions(recoveryManager);
        verifyNoInteractions(completionManager);
    }
}

package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.process.application.DeliveredTaskMergeGateProcessManager;
import com.agentx.agentxbackend.process.application.MergeGateCompletionProcessManager;
import com.agentx.agentxbackend.process.application.RunFinishedProcessManager;
import com.agentx.agentxbackend.process.application.RunNeedsInputProcessManager;
import com.agentx.agentxbackend.process.application.VerifyFailureRecoveryProcessManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

@Component
public class RunDomainEventListener {

    private final RunNeedsInputProcessManager runNeedsInputProcessManager;
    private final RunFinishedProcessManager runFinishedProcessManager;
    private final DeliveredTaskMergeGateProcessManager deliveredTaskMergeGateProcessManager;
    private final MergeGateCompletionProcessManager mergeGateCompletionProcessManager;
    private final VerifyFailureRecoveryProcessManager verifyFailureRecoveryProcessManager;

    public RunDomainEventListener(
        RunNeedsInputProcessManager runNeedsInputProcessManager,
        RunFinishedProcessManager runFinishedProcessManager,
        DeliveredTaskMergeGateProcessManager deliveredTaskMergeGateProcessManager,
        MergeGateCompletionProcessManager mergeGateCompletionProcessManager,
        VerifyFailureRecoveryProcessManager verifyFailureRecoveryProcessManager
    ) {
        this.runNeedsInputProcessManager = runNeedsInputProcessManager;
        this.runFinishedProcessManager = runFinishedProcessManager;
        this.deliveredTaskMergeGateProcessManager = deliveredTaskMergeGateProcessManager;
        this.mergeGateCompletionProcessManager = mergeGateCompletionProcessManager;
        this.verifyFailureRecoveryProcessManager = verifyFailureRecoveryProcessManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunNeedsDecision(RunNeedsDecisionEvent event) {
        runNeedsInputProcessManager.handle(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunNeedsClarification(RunNeedsClarificationEvent event) {
        runNeedsInputProcessManager.handle(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunFinished(RunFinishedEvent event) {
        runFinishedProcessManager.handle(event);
        if (event.runKind() == RunKind.IMPL && isSucceeded(event)) {
            deliveredTaskMergeGateProcessManager.onTaskDelivered(event.taskId());
        }
        if (event.runKind() == RunKind.VERIFY && isFailed(event)) {
            verifyFailureRecoveryProcessManager.onVerifyFailed(event);
            return;
        }
        if (event.runKind() == RunKind.VERIFY && isSucceeded(event) && hasMergeCandidate(event)) {
            mergeGateCompletionProcessManager.onVerifySucceeded(
                event.taskId(),
                event.runId(),
                event.baseCommit()
            );
        }
    }

    private static boolean isSucceeded(RunFinishedEvent event) {
        return event.payload() != null
            && event.payload().resultStatus() != null
            && "SUCCEEDED".equals(event.payload().resultStatus().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean isFailed(RunFinishedEvent event) {
        return event.payload() != null
            && event.payload().resultStatus() != null
            && "FAILED".equals(event.payload().resultStatus().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean hasMergeCandidate(RunFinishedEvent event) {
        return event.baseCommit() != null && !event.baseCommit().isBlank();
    }
}

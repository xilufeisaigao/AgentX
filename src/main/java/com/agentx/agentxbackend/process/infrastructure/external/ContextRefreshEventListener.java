package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.process.application.ContextRefreshProcessManager;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ContextRefreshEventListener {

    private final ContextRefreshProcessManager processManager;

    public ContextRefreshEventListener(ContextRefreshProcessManager processManager) {
        this.processManager = processManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequirementConfirmed(RequirementConfirmedEvent event) {
        processManager.handleRequirementConfirmed(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketEventAppended(TicketEventAppendedEvent event) {
        processManager.handleTicketEvent(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRunFinished(RunFinishedEvent event) {
        processManager.handleRunFinished(event);
    }
}

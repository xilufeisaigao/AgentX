package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.process.application.ContextRefreshProcessManager;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ContextRefreshEventListenerTest {

    @Test
    void onRequirementConfirmedShouldDelegateToProcessManager() {
        ContextRefreshProcessManager manager = mock(ContextRefreshProcessManager.class);
        ContextRefreshEventListener listener = new ContextRefreshEventListener(manager);
        RequirementConfirmedEvent event = new RequirementConfirmedEvent("SES-1", "REQ-1", 1, null);

        listener.onRequirementConfirmed(event);

        verify(manager).handleRequirementConfirmed(event);
    }

    @Test
    void onTicketEventAppendedShouldDelegateToProcessManager() {
        ContextRefreshProcessManager manager = mock(ContextRefreshProcessManager.class);
        ContextRefreshEventListener listener = new ContextRefreshEventListener(manager);
        TicketEventAppendedEvent event = new TicketEventAppendedEvent(
            "TCK-1",
            "SES-1",
            TicketType.ARCH_REVIEW,
            "architect_agent",
            TicketEventType.USER_RESPONDED,
            TicketStatus.IN_PROGRESS
        );

        listener.onTicketEventAppended(event);

        verify(manager).handleTicketEvent(event);
    }

    @Test
    void onRunFinishedShouldDelegateToProcessManager() {
        ContextRefreshProcessManager manager = mock(ContextRefreshProcessManager.class);
        ContextRefreshEventListener listener = new ContextRefreshEventListener(manager);
        RunFinishedEvent event = new RunFinishedEvent(
            "RUN-1",
            "TASK-1",
            new RunFinishedPayload("SUCCEEDED", "ok", "abc", "[]")
        );

        listener.onRunFinished(event);

        verify(manager).handleRunFinished(event);
    }
}

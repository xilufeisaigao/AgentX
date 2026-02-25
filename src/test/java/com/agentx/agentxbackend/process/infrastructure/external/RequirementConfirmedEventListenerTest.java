package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.RequirementConfirmedProcessManager;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequirementConfirmedEventListenerTest {

    @Test
    void onRequirementConfirmedShouldDelegateToProcessManager() {
        RequirementConfirmedProcessManager manager = mock(RequirementConfirmedProcessManager.class);
        RequirementConfirmedEventListener listener = new RequirementConfirmedEventListener(manager);
        RequirementConfirmedEvent event = new RequirementConfirmedEvent("SES-1", "REQ-1", 1, null);

        listener.onRequirementConfirmed(event);

        verify(manager).handle(event);
    }
}

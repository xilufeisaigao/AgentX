package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.RequirementHandoffProcessManager;
import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RequirementHandoffRequestedEventListener {

    private final RequirementHandoffProcessManager processManager;

    public RequirementHandoffRequestedEventListener(RequirementHandoffProcessManager processManager) {
        this.processManager = processManager;
    }

    @EventListener
    public void onRequirementHandoffRequested(RequirementHandoffRequestedEvent event) {
        processManager.handle(event);
    }
}

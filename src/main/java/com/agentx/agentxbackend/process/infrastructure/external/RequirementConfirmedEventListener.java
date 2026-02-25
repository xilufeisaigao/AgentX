package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.RequirementConfirmedProcessManager;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RequirementConfirmedEventListener {

    private final RequirementConfirmedProcessManager processManager;

    public RequirementConfirmedEventListener(RequirementConfirmedProcessManager processManager) {
        this.processManager = processManager;
    }

    @EventListener
    public void onRequirementConfirmed(RequirementConfirmedEvent event) {
        processManager.handle(event);
    }
}

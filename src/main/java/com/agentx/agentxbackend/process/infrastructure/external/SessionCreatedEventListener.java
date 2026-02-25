package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.SessionBootstrapInitProcessManager;
import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SessionCreatedEventListener {

    private final SessionBootstrapInitProcessManager processManager;

    public SessionCreatedEventListener(SessionBootstrapInitProcessManager processManager) {
        this.processManager = processManager;
    }

    @EventListener
    public void onSessionCreated(SessionCreatedEvent event) {
        processManager.handle(event);
    }
}

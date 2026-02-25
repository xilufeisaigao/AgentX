package com.agentx.agentxbackend.session.infrastructure.external;

import com.agentx.agentxbackend.session.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringSessionDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringSessionDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(SessionCreatedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}

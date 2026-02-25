package com.agentx.agentxbackend.requirement.infrastructure.external;

import com.agentx.agentxbackend.requirement.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(RequirementConfirmedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(RequirementHandoffRequestedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}

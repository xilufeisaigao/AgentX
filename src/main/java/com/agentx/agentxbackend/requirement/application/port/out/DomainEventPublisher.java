package com.agentx.agentxbackend.requirement.application.port.out;

import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;

public interface DomainEventPublisher {

    void publish(RequirementConfirmedEvent event);

    void publish(RequirementHandoffRequestedEvent event);
}

package com.agentx.agentxbackend.session.application.port.out;

import com.agentx.agentxbackend.session.domain.event.SessionCreatedEvent;

public interface DomainEventPublisher {

    void publish(SessionCreatedEvent event);
}

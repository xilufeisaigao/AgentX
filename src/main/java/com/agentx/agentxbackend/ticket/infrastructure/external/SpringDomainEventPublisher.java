package com.agentx.agentxbackend.ticket.infrastructure.external;

import com.agentx.agentxbackend.ticket.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component("ticketDomainEventPublisher")
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(TicketEventAppendedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}

package com.agentx.agentxbackend.ticket.application.port.out;

import com.agentx.agentxbackend.ticket.domain.event.TicketEventAppendedEvent;

public interface DomainEventPublisher {

    void publish(TicketEventAppendedEvent event);
}

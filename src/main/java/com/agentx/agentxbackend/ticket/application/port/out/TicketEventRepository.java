package com.agentx.agentxbackend.ticket.application.port.out;

import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;

import java.util.List;

public interface TicketEventRepository {

    TicketEvent save(TicketEvent event);

    List<TicketEvent> findByTicketId(String ticketId);
}

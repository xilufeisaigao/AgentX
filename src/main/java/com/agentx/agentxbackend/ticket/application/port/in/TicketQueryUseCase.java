package com.agentx.agentxbackend.ticket.application.port.in;

import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;

import java.util.List;

public interface TicketQueryUseCase {

    List<Ticket> listBySession(String sessionId, String status, String assigneeRole, String type);

    Ticket findById(String ticketId);

    List<TicketEvent> listEvents(String ticketId);
}

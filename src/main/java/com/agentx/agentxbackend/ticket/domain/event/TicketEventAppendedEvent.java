package com.agentx.agentxbackend.ticket.domain.event;

import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;

public record TicketEventAppendedEvent(
    String ticketId,
    String sessionId,
    TicketType ticketType,
    String assigneeRole,
    TicketEventType eventType,
    TicketStatus ticketStatus
) {
}

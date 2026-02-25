package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RequirementHandoffProcessManager {

    private final TicketCommandUseCase ticketCommandUseCase;

    public RequirementHandoffProcessManager(TicketCommandUseCase ticketCommandUseCase) {
        this.ticketCommandUseCase = ticketCommandUseCase;
    }

    public void handle(RequirementHandoffRequestedEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String title = "HANDOFF: architecture review needed";
        String payloadJson = RequirementTicketPayloadBuilder.buildHandoffPayload(event);
        Ticket ticket = ticketCommandUseCase.createTicket(
            event.sessionId(),
            TicketType.HANDOFF,
            title,
            "requirement_agent",
            "architect_agent",
            event.requirementDocId(),
            event.requirementDocVersion(),
            payloadJson
        );
        ticketCommandUseCase.appendEvent(
            ticket.ticketId(),
            "requirement_agent",
            "COMMENT",
            "Architecture-layer request handed off to architect agent.",
            RequirementTicketPayloadBuilder.buildHandoffEventData(event)
        );
    }
}

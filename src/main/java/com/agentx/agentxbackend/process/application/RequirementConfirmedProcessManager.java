package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class RequirementConfirmedProcessManager {

    private final TicketCommandUseCase ticketCommandUseCase;

    public RequirementConfirmedProcessManager(TicketCommandUseCase ticketCommandUseCase) {
        this.ticketCommandUseCase = ticketCommandUseCase;
    }

    public void handle(RequirementConfirmedEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        String title = "ARCH_REVIEW: " + event.docId() + "@" + event.confirmedVersion();
        String payloadJson = RequirementTicketPayloadBuilder.buildArchReviewPayload(event);
        Ticket ticket = ticketCommandUseCase.createTicket(
            event.sessionId(),
            TicketType.ARCH_REVIEW,
            title,
            "requirement_agent",
            "architect_agent",
            event.docId(),
            event.confirmedVersion(),
            payloadJson
        );
        String eventBody = "Requirement baseline confirmed at version "
            + event.confirmedVersion() + ", architecture review requested.";
        ticketCommandUseCase.appendEvent(
            ticket.ticketId(),
            "requirement_agent",
            "COMMENT",
            eventBody,
            RequirementTicketPayloadBuilder.buildArchReviewEventData(event)
        );
    }
}

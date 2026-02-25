package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.requirement.domain.event.RequirementHandoffRequestedEvent;
import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementHandoffProcessManagerTest {

    @Mock
    private TicketCommandUseCase ticketCommandUseCase;

    @Test
    void handleShouldCreateHandoffTicketToArchitectAgent() {
        RequirementHandoffProcessManager manager = new RequirementHandoffProcessManager(ticketCommandUseCase);
        RequirementHandoffRequestedEvent event = new RequirementHandoffRequestedEvent(
            "SES-200",
            "REQ-200",
            4,
            "Please design database sharding plan",
            "Architecture-related change"
        );
        Ticket createdTicket = new Ticket(
            "TCK-200",
            "SES-200",
            TicketType.HANDOFF,
            TicketStatus.OPEN,
            "HANDOFF: architecture review needed",
            "requirement_agent",
            "architect_agent",
            "REQ-200",
            4,
            "{\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\"}",
            null,
            null,
            Instant.parse("2026-02-21T00:00:00Z"),
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(ticketCommandUseCase.createTicket(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(createdTicket);

        manager.handle(event);

        ArgumentCaptor<String> sessionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TicketType> typeCaptor = ArgumentCaptor.forClass(TicketType.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> createdByCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> assigneeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requirementDocIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> requirementDocVerCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

        verify(ticketCommandUseCase).createTicket(
            sessionCaptor.capture(),
            typeCaptor.capture(),
            titleCaptor.capture(),
            createdByCaptor.capture(),
            assigneeCaptor.capture(),
            requirementDocIdCaptor.capture(),
            requirementDocVerCaptor.capture(),
            payloadCaptor.capture()
        );

        assertTrue(sessionCaptor.getValue().equals("SES-200"));
        assertTrue(typeCaptor.getValue() == TicketType.HANDOFF);
        assertTrue(titleCaptor.getValue().contains("HANDOFF"));
        assertTrue(createdByCaptor.getValue().equals("requirement_agent"));
        assertTrue(assigneeCaptor.getValue().equals("architect_agent"));
        assertTrue(requirementDocIdCaptor.getValue().equals("REQ-200"));
        assertTrue(requirementDocVerCaptor.getValue().equals(4));
        assertTrue(payloadCaptor.getValue().contains("\"kind\":\"handoff_packet\""));
        assertTrue(payloadCaptor.getValue().contains("\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\""));
        assertTrue(payloadCaptor.getValue().contains("\"summary\":\"Architecture-related change\""));
        verify(ticketCommandUseCase).appendEvent(
            "TCK-200",
            "requirement_agent",
            "COMMENT",
            "Architecture-layer request handed off to architect agent.",
            "{\"trigger\":\"ARCHITECTURE_CHANGE_REQUESTED\",\"reason\":\"Architecture-related change\"}"
        );
        verifyNoMoreInteractions(ticketCommandUseCase);
    }

    @Test
    void handleShouldRejectNullEvent() {
        RequirementHandoffProcessManager manager = new RequirementHandoffProcessManager(ticketCommandUseCase);

        assertThrows(NullPointerException.class, () -> manager.handle(null));
    }
}

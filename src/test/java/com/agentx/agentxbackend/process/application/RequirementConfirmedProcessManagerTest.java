package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.requirement.domain.event.RequirementConfirmedEvent;
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
class RequirementConfirmedProcessManagerTest {

    @Mock
    private TicketCommandUseCase ticketCommandUseCase;

    @Test
    void handleShouldCreateArchReviewTicketWithRequirementPayload() {
        RequirementConfirmedProcessManager manager = new RequirementConfirmedProcessManager(ticketCommandUseCase);
        RequirementConfirmedEvent event = new RequirementConfirmedEvent("SES-100", "REQ-100", 7, 6);
        Ticket createdTicket = new Ticket(
            "TCK-100",
            "SES-100",
            TicketType.ARCH_REVIEW,
            TicketStatus.OPEN,
            "ARCH_REVIEW: REQ-100@7",
            "requirement_agent",
            "architect_agent",
            "REQ-100",
            7,
            "{\"trigger\":\"REQUIREMENT_CONFIRMED\"}",
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

        assertTrue(sessionCaptor.getValue().equals("SES-100"));
        assertTrue(typeCaptor.getValue() == TicketType.ARCH_REVIEW);
        assertTrue(titleCaptor.getValue().contains("REQ-100@7"));
        assertTrue(createdByCaptor.getValue().equals("requirement_agent"));
        assertTrue(assigneeCaptor.getValue().equals("architect_agent"));
        assertTrue(requirementDocIdCaptor.getValue().equals("REQ-100"));
        assertTrue(requirementDocVerCaptor.getValue().equals(7));
        assertTrue(payloadCaptor.getValue().contains("\"kind\":\"handoff_packet\""));
        assertTrue(payloadCaptor.getValue().contains("\"trigger\":\"REQUIREMENT_CONFIRMED\""));
        assertTrue(payloadCaptor.getValue().contains("\"doc_id\":\"REQ-100\""));
        assertTrue(payloadCaptor.getValue().contains("\"from_confirmed_version\":6"));
        verify(ticketCommandUseCase).appendEvent(
            "TCK-100",
            "requirement_agent",
            "COMMENT",
            "Requirement baseline confirmed at version 7, architecture review requested.",
            "{\"trigger\":\"REQUIREMENT_CONFIRMED\",\"doc_id\":\"REQ-100\",\"to_version\":7}"
        );
        verifyNoMoreInteractions(ticketCommandUseCase);
    }

    @Test
    void handleShouldRejectNullEvent() {
        RequirementConfirmedProcessManager manager = new RequirementConfirmedProcessManager(ticketCommandUseCase);

        assertThrows(NullPointerException.class, () -> manager.handle(null));
    }
}

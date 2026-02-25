package com.agentx.agentxbackend.ticket.api;

import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketEventType;
import com.agentx.agentxbackend.ticket.domain.model.TicketStatus;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketControllerMockTest {

    @Mock
    private TicketCommandUseCase commandUseCase;
    @Mock
    private TicketQueryUseCase queryUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TicketController controller = new TicketController(commandUseCase, queryUseCase);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new TicketExceptionHandler())
            .build();
    }

    @Test
    void listTicketsBySessionShouldReturnArray() throws Exception {
        Ticket ticket = sampleTicket("TCK-1", TicketStatus.WAITING_USER);
        when(queryUseCase.listBySession("SES-1", "WAITING_USER", "architect_agent", "ARCH_REVIEW"))
            .thenReturn(List.of(ticket));

        mockMvc.perform(get("/api/v0/sessions/SES-1/tickets")
                .param("status", "WAITING_USER")
                .param("assignee_role", "architect_agent")
                .param("type", "ARCH_REVIEW"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].ticket_id").value("TCK-1"))
            .andExpect(jsonPath("$[0].status").value("WAITING_USER"));
    }

    @Test
    void createTicketShouldReturnTicket() throws Exception {
        Ticket ticket = sampleTicket("TCK-2", TicketStatus.OPEN);
        when(commandUseCase.createTicket(
            eq("SES-1"),
            eq(TicketType.ARCH_REVIEW),
            eq("arch review"),
            eq("requirement_agent"),
            eq("architect_agent"),
            eq("REQ-1"),
            eq(2),
            any()
        )).thenReturn(ticket);

        mockMvc.perform(post("/api/v0/sessions/SES-1/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type":"ARCH_REVIEW",
                      "title":"arch review",
                      "created_by_role":"requirement_agent",
                      "assignee_role":"architect_agent",
                      "requirement_doc_id":"REQ-1",
                      "requirement_doc_ver":2,
                      "payload_json":"{}"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket_id").value("TCK-2"))
            .andExpect(jsonPath("$.type").value("ARCH_REVIEW"));
    }

    @Test
    void claimTicketShouldReturnUpdatedTicket() throws Exception {
        Ticket claimed = sampleTicket("TCK-3", TicketStatus.IN_PROGRESS);
        when(commandUseCase.claimTicket("TCK-3", "agent-1", 120)).thenReturn(claimed);

        mockMvc.perform(post("/api/v0/tickets/TCK-3/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"claimed_by":"agent-1","lease_seconds":120}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket_id").value("TCK-3"))
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void appendTicketEventShouldReturnEvent() throws Exception {
        TicketEvent event = new TicketEvent(
            "TEV-1",
            "TCK-4",
            TicketEventType.USER_RESPONDED,
            "user",
            "picked A",
            "{\"option\":\"A\"}",
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(commandUseCase.appendEvent(
            "TCK-4",
            "user",
            "USER_RESPONDED",
            "picked A",
            "{\"option\":\"A\"}"
        )).thenReturn(event);

        mockMvc.perform(post("/api/v0/tickets/TCK-4/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "event_type":"USER_RESPONDED",
                      "actor_role":"user",
                      "body":"picked A",
                      "data_json":"{\\"option\\":\\"A\\"}"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event_id").value("TEV-1"))
            .andExpect(jsonPath("$.event_type").value("USER_RESPONDED"));
    }

    @Test
    void listTicketEventsShouldReturnArray() throws Exception {
        TicketEvent event = new TicketEvent(
            "TEV-2",
            "TCK-9",
            TicketEventType.DECISION_REQUESTED,
            "architect_agent",
            "need decision",
            "{\"request_kind\":\"DECISION\"}",
            Instant.parse("2026-02-21T00:00:00Z")
        );
        when(queryUseCase.listEvents("TCK-9")).thenReturn(List.of(event));

        mockMvc.perform(get("/api/v0/tickets/TCK-9/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].event_id").value("TEV-2"))
            .andExpect(jsonPath("$[0].event_type").value("DECISION_REQUESTED"));
    }

    @Test
    void claimTicketShouldReturnNotFoundWhenMissing() throws Exception {
        when(commandUseCase.claimTicket("TCK-404", "agent", 300))
            .thenThrow(new NoSuchElementException("Ticket not found"));

        mockMvc.perform(post("/api/v0/tickets/TCK-404/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"claimed_by":"agent"}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listTicketsShouldReturnBadRequestOnInvalidStatus() throws Exception {
        when(queryUseCase.listBySession("SES-1", "bad", null, null))
            .thenThrow(new IllegalArgumentException("No enum constant"));

        mockMvc.perform(get("/api/v0/sessions/SES-1/tickets").param("status", "bad"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    private static Ticket sampleTicket(String ticketId, TicketStatus status) {
        Instant now = Instant.parse("2026-02-21T00:00:00Z");
        return new Ticket(
            ticketId,
            "SES-1",
            TicketType.ARCH_REVIEW,
            status,
            "title",
            "requirement_agent",
            "architect_agent",
            "REQ-1",
            2,
            "{}",
            "agent-1",
            now.plusSeconds(300),
            now,
            now
        );
    }
}

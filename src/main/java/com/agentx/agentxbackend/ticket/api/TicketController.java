package com.agentx.agentxbackend.ticket.api;

import com.agentx.agentxbackend.ticket.application.port.in.TicketCommandUseCase;
import com.agentx.agentxbackend.ticket.application.port.in.TicketQueryUseCase;
import com.agentx.agentxbackend.ticket.domain.model.Ticket;
import com.agentx.agentxbackend.ticket.domain.model.TicketEvent;
import com.agentx.agentxbackend.ticket.domain.model.TicketType;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class TicketController {

    private final TicketCommandUseCase commandUseCase;
    private final TicketQueryUseCase queryUseCase;

    public TicketController(TicketCommandUseCase commandUseCase, TicketQueryUseCase queryUseCase) {
        this.commandUseCase = commandUseCase;
        this.queryUseCase = queryUseCase;
    }

    @GetMapping("/api/v0/sessions/{sessionId}/tickets")
    public ResponseEntity<List<TicketResponse>> listTicketsBySession(
        @PathVariable String sessionId,
        @RequestParam(required = false) String status,
        @RequestParam(name = "assignee_role", required = false) String assigneeRole,
        @RequestParam(required = false) String type
    ) {
        List<TicketResponse> responses = queryUseCase.listBySession(sessionId, status, assigneeRole, type)
            .stream()
            .map(TicketResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/api/v0/sessions/{sessionId}/tickets")
    public ResponseEntity<TicketResponse> createTicket(
        @PathVariable String sessionId,
        @RequestBody CreateTicketRequest request
    ) {
        Ticket ticket = commandUseCase.createTicket(
            sessionId,
            TicketType.valueOf(request.type()),
            request.title(),
            request.createdByRole(),
            request.assigneeRole(),
            request.requirementDocId(),
            request.requirementDocVer(),
            request.payloadJson()
        );
        return ResponseEntity.ok(TicketResponse.from(ticket));
    }

    @PostMapping("/api/v0/tickets/{ticketId}/claim")
    public ResponseEntity<TicketResponse> claimTicket(
        @PathVariable String ticketId,
        @RequestBody ClaimTicketRequest request
    ) {
        int leaseSeconds = request.leaseSeconds() == null ? 300 : request.leaseSeconds();
        Ticket ticket = commandUseCase.claimTicket(ticketId, request.claimedBy(), leaseSeconds);
        return ResponseEntity.ok(TicketResponse.from(ticket));
    }

    @GetMapping("/api/v0/tickets/{ticketId}/events")
    public ResponseEntity<List<TicketEventResponse>> listTicketEvents(@PathVariable String ticketId) {
        List<TicketEventResponse> responses = queryUseCase.listEvents(ticketId)
            .stream()
            .map(TicketEventResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/api/v0/tickets/{ticketId}/events")
    public ResponseEntity<TicketEventResponse> appendTicketEvent(
        @PathVariable String ticketId,
        @RequestBody AppendTicketEventRequest request
    ) {
        TicketEvent event = commandUseCase.appendEvent(
            ticketId,
            request.actorRole(),
            request.eventType(),
            request.body(),
            request.dataJson()
        );
        return ResponseEntity.ok(TicketEventResponse.from(event));
    }

    public record CreateTicketRequest(
        String type,
        String title,
        @JsonProperty("created_by_role") String createdByRole,
        @JsonProperty("assignee_role") String assigneeRole,
        @JsonProperty("requirement_doc_id") String requirementDocId,
        @JsonProperty("requirement_doc_ver") Integer requirementDocVer,
        @JsonProperty("payload_json") String payloadJson
    ) {
    }

    public record ClaimTicketRequest(
        @JsonProperty("claimed_by") String claimedBy,
        @JsonProperty("lease_seconds") Integer leaseSeconds
    ) {
    }

    public record AppendTicketEventRequest(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_role") String actorRole,
        String body,
        @JsonProperty("data_json") String dataJson
    ) {
    }

    public record TicketResponse(
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("session_id") String sessionId,
        String type,
        String status,
        String title,
        @JsonProperty("created_by_role") String createdByRole,
        @JsonProperty("assignee_role") String assigneeRole,
        @JsonProperty("requirement_doc_id") String requirementDocId,
        @JsonProperty("requirement_doc_ver") Integer requirementDocVer,
        @JsonProperty("payload_json") String payloadJson,
        @JsonProperty("claimed_by") String claimedBy,
        @JsonProperty("lease_until") Instant leaseUntil,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static TicketResponse from(Ticket ticket) {
            return new TicketResponse(
                ticket.ticketId(),
                ticket.sessionId(),
                ticket.type().name(),
                ticket.status().name(),
                ticket.title(),
                ticket.createdByRole(),
                ticket.assigneeRole(),
                ticket.requirementDocId(),
                ticket.requirementDocVer(),
                ticket.payloadJson(),
                ticket.claimedBy(),
                ticket.leaseUntil(),
                ticket.createdAt(),
                ticket.updatedAt()
            );
        }
    }

    public record TicketEventResponse(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("ticket_id") String ticketId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("actor_role") String actorRole,
        String body,
        @JsonProperty("data_json") String dataJson,
        @JsonProperty("created_at") Instant createdAt
    ) {
        static TicketEventResponse from(TicketEvent event) {
            return new TicketEventResponse(
                event.eventId(),
                event.ticketId(),
                event.eventType().name(),
                event.actorRole(),
                event.body(),
                event.dataJson(),
                event.createdAt()
            );
        }
    }
}

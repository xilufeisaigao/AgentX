package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.process.application.ArchitectTicketAutoProcessorService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ArchitectAutomationController {

    private final ArchitectTicketAutoProcessorService autoProcessorService;

    public ArchitectAutomationController(ArchitectTicketAutoProcessorService autoProcessorService) {
        this.autoProcessorService = autoProcessorService;
    }

    @PostMapping("/api/v0/architect/auto-process")
    public ResponseEntity<AutoProcessResponse> autoProcess(@RequestBody(required = false) AutoProcessRequest request) {
        String sessionId = request == null ? null : request.sessionId();
        int maxTickets = request == null || request.maxTickets() == null ? 8 : request.maxTickets();
        ArchitectTicketAutoProcessorService.AutoProcessResult result = autoProcessorService.processOpenArchitectTickets(
            sessionId,
            maxTickets
        );
        AutoProcessResponse response = new AutoProcessResponse(
            result.processedCount(),
            result.processedTicketIds(),
            result.skippedTicketIds()
        );
        return ResponseEntity.ok(response);
    }

    public record AutoProcessRequest(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("max_tickets") Integer maxTickets
    ) {
    }

    public record AutoProcessResponse(
        @JsonProperty("processed_count") int processedCount,
        @JsonProperty("processed_ticket_ids") List<String> processedTicketIds,
        @JsonProperty("skipped_ticket_ids") List<String> skippedTicketIds
    ) {
    }
}

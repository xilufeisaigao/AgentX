package com.agentx.agentxbackend.query.api;

import com.agentx.agentxbackend.query.application.port.in.ProgressQueryUseCase;
import com.agentx.agentxbackend.query.domain.model.RunTimelineView;
import com.agentx.agentxbackend.query.domain.model.SessionProgressView;
import com.agentx.agentxbackend.query.domain.model.TaskBoardView;
import com.agentx.agentxbackend.query.domain.model.TicketInboxView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProgressQueryController {

    private final ProgressQueryUseCase useCase;

    public ProgressQueryController(ProgressQueryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/api/v0/sessions/{sessionId}/progress")
    public ResponseEntity<SessionProgressView> getSessionProgress(@PathVariable String sessionId) {
        return ResponseEntity.ok(useCase.getSessionProgress(sessionId));
    }

    @GetMapping("/api/v0/sessions/{sessionId}/ticket-inbox")
    public ResponseEntity<TicketInboxView> getTicketInbox(
        @PathVariable String sessionId,
        @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(useCase.getTicketInbox(sessionId, status));
    }

    @GetMapping("/api/v0/sessions/{sessionId}/task-board")
    public ResponseEntity<TaskBoardView> getTaskBoard(@PathVariable String sessionId) {
        return ResponseEntity.ok(useCase.getTaskBoard(sessionId));
    }

    @GetMapping("/api/v0/sessions/{sessionId}/run-timeline")
    public ResponseEntity<RunTimelineView> getRunTimeline(
        @PathVariable String sessionId,
        @RequestParam(required = false) Integer limit
    ) {
        int resolvedLimit = limit == null ? 40 : limit;
        return ResponseEntity.ok(useCase.getRunTimeline(sessionId, resolvedLimit));
    }
}

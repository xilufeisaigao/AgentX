package com.agentx.agentxbackend.session.api;

import com.agentx.agentxbackend.session.application.port.in.SessionCommandUseCase;
import com.agentx.agentxbackend.session.domain.model.Session;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class SessionCommandController {

    private final SessionCommandUseCase useCase;

    public SessionCommandController(SessionCommandUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/sessions")
    public ResponseEntity<SessionResponse> createSession(@RequestBody CreateSessionRequest request) {
        Session session = useCase.createSession(request.title());
        return ResponseEntity.ok(SessionResponse.from(session));
    }

    @PostMapping("/api/v0/sessions/{sessionId}/pause")
    public ResponseEntity<SessionResponse> pauseSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(SessionResponse.from(useCase.pauseSession(sessionId)));
    }

    @PostMapping("/api/v0/sessions/{sessionId}/resume")
    public ResponseEntity<SessionResponse> resumeSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(SessionResponse.from(useCase.resumeSession(sessionId)));
    }

    @PostMapping("/api/v0/sessions/{sessionId}/complete")
    public ResponseEntity<SessionResponse> completeSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(SessionResponse.from(useCase.completeSession(sessionId)));
    }

    public record CreateSessionRequest(String title) {
    }

    public record SessionResponse(
        @JsonProperty("session_id") String sessionId,
        String title,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static SessionResponse from(Session session) {
            return new SessionResponse(
                session.sessionId(),
                session.title(),
                session.status().name(),
                session.createdAt(),
                session.updatedAt()
            );
        }
    }
}

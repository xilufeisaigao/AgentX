package com.agentx.agentxbackend.session.api;

import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import com.agentx.agentxbackend.session.application.query.SessionHistoryView;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class SessionQueryController {

    private final SessionHistoryQueryUseCase useCase;

    public SessionQueryController(SessionHistoryQueryUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/api/v0/sessions")
    public ResponseEntity<List<SessionHistoryResponse>> listSessionsWithCurrentRequirementDoc() {
        List<SessionHistoryResponse> responses = useCase.listSessionsWithCurrentRequirementDoc()
            .stream()
            .map(SessionHistoryResponse::from)
            .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/api/v0/sessions/{sessionId}")
    public ResponseEntity<SessionHistoryResponse> getSessionWithCurrentRequirementDoc(@PathVariable String sessionId) {
        SessionHistoryView view = useCase.findSessionWithCurrentRequirementDoc(sessionId)
            .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        return ResponseEntity.ok(SessionHistoryResponse.from(view));
    }

    public record SessionHistoryResponse(
        @JsonProperty("session_id") String sessionId,
        String title,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("current_requirement_doc") CurrentRequirementDocResponse currentRequirementDoc
    ) {
        static SessionHistoryResponse from(SessionHistoryView view) {
            return new SessionHistoryResponse(
                view.sessionId(),
                view.title(),
                view.status(),
                view.createdAt(),
                view.updatedAt(),
                CurrentRequirementDocResponse.from(view.currentRequirementDoc())
            );
        }
    }

    public record CurrentRequirementDocResponse(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("current_version") int currentVersion,
        @JsonProperty("confirmed_version") Integer confirmedVersion,
        String status,
        String title,
        String content,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static CurrentRequirementDocResponse from(SessionHistoryView.CurrentRequirementDoc requirementDoc) {
            if (requirementDoc == null) {
                return null;
            }
            return new CurrentRequirementDocResponse(
                requirementDoc.docId(),
                requirementDoc.currentVersion(),
                requirementDoc.confirmedVersion(),
                requirementDoc.status(),
                requirementDoc.title(),
                requirementDoc.content(),
                requirementDoc.updatedAt()
            );
        }
    }
}

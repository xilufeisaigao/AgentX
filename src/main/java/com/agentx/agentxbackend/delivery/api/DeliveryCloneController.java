package com.agentx.agentxbackend.delivery.api;

import com.agentx.agentxbackend.delivery.application.port.in.DeliveryClonePublishUseCase;
import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.NoSuchElementException;

@RestController
public class DeliveryCloneController {

    private final DeliveryClonePublishUseCase useCase;

    public DeliveryCloneController(DeliveryClonePublishUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/sessions/{sessionId}/delivery/clone-repo")
    public ResponseEntity<DeliveryCloneResponse> publishCloneRepo(@PathVariable String sessionId) {
        DeliveryClonePublication publication = useCase.publish(sessionId);
        return ResponseEntity.ok(DeliveryCloneResponse.from(publication));
    }

    @GetMapping("/api/v0/sessions/{sessionId}/delivery/clone-repo")
    public ResponseEntity<DeliveryCloneResponse> getActiveCloneRepo(@PathVariable String sessionId) {
        DeliveryClonePublication publication = useCase.findActive(sessionId)
            .orElseThrow(() -> new NoSuchElementException("No active clone repository for session: " + sessionId));
        return ResponseEntity.ok(DeliveryCloneResponse.from(publication));
    }

    public record DeliveryCloneResponse(
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("repository_name") String repositoryName,
        @JsonProperty("clone_url") String cloneUrl,
        @JsonProperty("clone_command") String cloneCommand,
        @JsonProperty("published_at") Instant publishedAt,
        @JsonProperty("expires_at") Instant expiresAt
    ) {
        static DeliveryCloneResponse from(DeliveryClonePublication publication) {
            return new DeliveryCloneResponse(
                publication.sessionId(),
                publication.repositoryName(),
                publication.cloneUrl(),
                "git clone " + publication.cloneUrl(),
                publication.publishedAt(),
                publication.expiresAt()
            );
        }
    }
}

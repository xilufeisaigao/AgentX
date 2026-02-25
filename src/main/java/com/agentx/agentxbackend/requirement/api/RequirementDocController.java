package com.agentx.agentxbackend.requirement.api;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementDocUseCase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class RequirementDocController {

    private final RequirementDocUseCase useCase;

    public RequirementDocController(RequirementDocUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/sessions/{sessionId}/requirement-docs")
    public ResponseEntity<RequirementDocResponse> createRequirementDoc(
        @PathVariable String sessionId,
        @RequestBody CreateRequirementDocRequest request
    ) {
        RequirementDoc doc = useCase.createRequirementDoc(sessionId, request.title());
        return ResponseEntity.status(HttpStatus.CREATED).body(RequirementDocResponse.from(doc));
    }

    @PostMapping("/api/v0/requirement-docs/{docId}/versions")
    public ResponseEntity<RequirementDocVersionResponse> createVersion(
        @PathVariable String docId,
        @RequestBody CreateRequirementDocVersionRequest request
    ) {
        RequirementDocVersion version = useCase.createVersion(
            docId,
            request.content(),
            request.createdByRole()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(RequirementDocVersionResponse.from(version));
    }

    @PostMapping("/api/v0/requirement-docs/{docId}/confirm")
    public ResponseEntity<RequirementDocResponse> confirm(@PathVariable String docId) {
        RequirementDoc doc = useCase.confirm(docId);
        return ResponseEntity.ok(RequirementDocResponse.from(doc));
    }

    @PutMapping("/api/v0/requirement-docs/{docId}/content")
    public ResponseEntity<RequirementDocVersionResponse> overwriteCurrentContent(
        @PathVariable String docId,
        @RequestBody OverwriteRequirementDocContentRequest request
    ) {
        RequirementDocVersion version = useCase.createVersion(docId, request.content(), "user");
        return ResponseEntity.ok(RequirementDocVersionResponse.from(version));
    }

    public record CreateRequirementDocRequest(String title) {
    }

    public record CreateRequirementDocVersionRequest(
        String content,
        @JsonProperty("created_by_role") String createdByRole
    ) {
    }

    public record OverwriteRequirementDocContentRequest(String content) {
    }

    public record RequirementDocResponse(
        @JsonProperty("doc_id") String docId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("current_version") int currentVersion,
        @JsonProperty("confirmed_version") Integer confirmedVersion,
        String status,
        String title,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
    ) {
        static RequirementDocResponse from(RequirementDoc doc) {
            return new RequirementDocResponse(
                doc.docId(),
                doc.sessionId(),
                doc.currentVersion(),
                doc.confirmedVersion(),
                doc.status().name(),
                doc.title(),
                doc.createdAt(),
                doc.updatedAt()
            );
        }
    }

    public record RequirementDocVersionResponse(
        @JsonProperty("doc_id") String docId,
        int version,
        String content,
        @JsonProperty("created_by_role") String createdByRole,
        @JsonProperty("created_at") Instant createdAt
    ) {
        static RequirementDocVersionResponse from(RequirementDocVersion version) {
            return new RequirementDocVersionResponse(
                version.docId(),
                version.version(),
                version.content(),
                version.createdByRole(),
                version.createdAt()
            );
        }
    }
}

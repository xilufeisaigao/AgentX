package com.agentx.agentxbackend.requirement.api;

import com.agentx.agentxbackend.requirement.application.port.in.RequirementAgentUseCase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementAgentPhase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RequirementAgentController {

    private final RequirementAgentUseCase useCase;

    public RequirementAgentController(RequirementAgentUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/sessions/{sessionId}/requirement-agent/drafts")
    public ResponseEntity<GenerateDraftResponse> generateDraft(
        @PathVariable String sessionId,
        @RequestBody GenerateDraftRequest request
    ) {
        RequirementAgentUseCase.DraftResult result = useCase.generateDraft(
            new RequirementAgentUseCase.GenerateDraftCommand(
                sessionId,
                request.title(),
                request.userInput(),
                request.docId(),
                request.persist() == null || request.persist()
            )
        );
        HttpStatus status = result.persisted() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(GenerateDraftResponse.from(result));
    }

    public record GenerateDraftRequest(
        String title,
        @JsonProperty("user_input") String userInput,
        @JsonProperty("doc_id") String docId,
        Boolean persist
    ) {
    }

    public record GenerateDraftResponse(
        @JsonProperty("doc_id") String docId,
        Integer version,
        String status,
        String content,
        boolean persisted,
        String provider,
        String model,
        String phase,
        @JsonProperty("assistant_message") String assistantMessage,
        @JsonProperty("ready_to_draft") boolean readyToDraft,
        @JsonProperty("missing_information") List<String> missingInformation
    ) {
        static GenerateDraftResponse from(RequirementAgentUseCase.DraftResult result) {
            RequirementDoc doc = result.doc();
            RequirementDocVersion version = result.version();
            RequirementAgentPhase phase = result.phase();
            return new GenerateDraftResponse(
                doc == null ? null : doc.docId(),
                version == null ? null : version.version(),
                doc == null ? null : doc.status().name(),
                result.content(),
                result.persisted(),
                result.provider(),
                result.model(),
                phase == null ? null : phase.name(),
                result.assistantMessage(),
                result.readyToDraft(),
                result.missingInformation()
            );
        }
    }
}

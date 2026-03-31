package com.agentx.platform.runtime.agentkernel.requirement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RequirementConversationContext(
        String workflowRunId,
        String workflowTitle,
        String initialRequirementTitle,
        String initialRequirementContent,
        Optional<CurrentRequirementVersion> latestRequirementVersion,
        List<RequirementTicketTurn> answeredTicketHistory,
        String latestInteractionPhase,
        String latestHumanInput
) {

    public RequirementConversationContext {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowTitle, "workflowTitle must not be null");
        Objects.requireNonNull(initialRequirementTitle, "initialRequirementTitle must not be null");
        Objects.requireNonNull(initialRequirementContent, "initialRequirementContent must not be null");
        latestRequirementVersion = latestRequirementVersion == null ? Optional.empty() : latestRequirementVersion;
        answeredTicketHistory = List.copyOf(answeredTicketHistory == null ? List.of() : answeredTicketHistory);
    }

    public record CurrentRequirementVersion(
            int version,
            String title,
            String content,
            String status,
            String createdByActorType,
            String createdByActorId
    ) {
    }

    public record RequirementTicketTurn(
            String ticketId,
            String type,
            String phase,
            String title,
            Integer requirementDocVersion,
            List<String> gaps,
            List<String> questions,
            String answer
    ) {

        public RequirementTicketTurn {
            gaps = List.copyOf(gaps == null ? List.of() : gaps);
            questions = List.copyOf(questions == null ? List.of() : questions);
        }
    }
}

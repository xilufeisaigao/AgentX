package com.agentx.agentxbackend.requirement.application.port.out;

import java.util.List;

public interface RequirementDraftGeneratorPort {

    ConversationAssessment assessConversation(AssessConversationInput input);

    GeneratedDraft generate(GenerateDraftInput input);

    record AssessConversationInput(
        String title,
        String userInput,
        List<ConversationTurn> history,
        boolean userWantsDraft
    ) {
    }

    record ConversationTurn(
        String role,
        String content
    ) {
    }

    record ConversationAssessment(
        String assistantMessage,
        boolean readyForDraft,
        List<String> missingInformation,
        boolean needsHandoff,
        String handoffReason,
        String provider,
        String model
    ) {
    }

    record GenerateDraftInput(
        String title,
        String userInput,
        String existingContent,
        List<ConversationTurn> history
    ) {
    }

    record GeneratedDraft(
        String content,
        String provider,
        String model
    ) {
    }
}

package com.agentx.agentxbackend.process.application.port.out;

import java.util.List;

public interface ArchitectTicketProposalGeneratorPort {

    Proposal generate(GenerateInput input);

    record GenerateInput(
        String ticketId,
        String sessionId,
        String ticketType,
        String title,
        String requirementDocId,
        Integer requirementDocVer,
        String payloadJson,
        String requirementDocContent,
        List<ArchitectTicketEventContext> recentEvents
    ) {
    }

    record Proposal(
        String requestKind,
        String question,
        List<String> context,
        List<DecisionOption> options,
        Recommendation recommendation,
        String analysisSummary,
        String provider,
        String model
    ) {
    }

    record DecisionOption(
        String optionId,
        String title,
        List<String> pros,
        List<String> cons,
        List<String> risks,
        List<String> costNotes
    ) {
    }

    record Recommendation(
        String optionId,
        String reason
    ) {
    }
}

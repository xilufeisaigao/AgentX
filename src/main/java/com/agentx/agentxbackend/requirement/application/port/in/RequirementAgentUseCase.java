package com.agentx.agentxbackend.requirement.application.port.in;

import com.agentx.agentxbackend.requirement.domain.model.RequirementDoc;
import com.agentx.agentxbackend.requirement.domain.model.RequirementAgentPhase;
import com.agentx.agentxbackend.requirement.domain.model.RequirementDocVersion;

import java.util.List;

public interface RequirementAgentUseCase {

    DraftResult generateDraft(GenerateDraftCommand command);

    record GenerateDraftCommand(
        String sessionId,
        String title,
        String userInput,
        String docId,
        boolean persist
    ) {
    }

    record DraftResult(
        RequirementDoc doc,
        RequirementDocVersion version,
        String content,
        boolean persisted,
        String provider,
        String model,
        RequirementAgentPhase phase,
        String assistantMessage,
        boolean readyToDraft,
        List<String> missingInformation
    ) {
    }
}

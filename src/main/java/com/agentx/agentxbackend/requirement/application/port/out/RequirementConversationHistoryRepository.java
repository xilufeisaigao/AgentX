package com.agentx.agentxbackend.requirement.application.port.out;

import com.agentx.agentxbackend.requirement.application.port.out.RequirementDraftGeneratorPort.ConversationTurn;

import java.util.List;

public interface RequirementConversationHistoryRepository {

    List<ConversationTurn> load(String sessionId);

    void append(String sessionId, ConversationTurn turn);

    void clear(String sessionId);
}

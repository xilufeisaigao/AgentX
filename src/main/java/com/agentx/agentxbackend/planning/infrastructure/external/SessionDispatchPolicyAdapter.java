package com.agentx.agentxbackend.planning.infrastructure.external;

import com.agentx.agentxbackend.planning.application.port.out.SessionDispatchPolicyPort;
import com.agentx.agentxbackend.session.application.port.in.SessionHistoryQueryUseCase;
import org.springframework.stereotype.Component;

@Component
public class SessionDispatchPolicyAdapter implements SessionDispatchPolicyPort {

    private final SessionHistoryQueryUseCase sessionHistoryQueryUseCase;

    public SessionDispatchPolicyAdapter(SessionHistoryQueryUseCase sessionHistoryQueryUseCase) {
        this.sessionHistoryQueryUseCase = sessionHistoryQueryUseCase;
    }

    @Override
    public boolean isSessionDispatchable(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return sessionHistoryQueryUseCase.findSessionWithCurrentRequirementDoc(sessionId.trim())
            .map(view -> "ACTIVE".equalsIgnoreCase(view.status()))
            .orElse(false);
    }
}

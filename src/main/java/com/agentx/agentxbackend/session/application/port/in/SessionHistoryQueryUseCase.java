package com.agentx.agentxbackend.session.application.port.in;

import com.agentx.agentxbackend.session.application.query.SessionHistoryView;

import java.util.List;
import java.util.Optional;

public interface SessionHistoryQueryUseCase {

    List<SessionHistoryView> listSessionsWithCurrentRequirementDoc();

    Optional<SessionHistoryView> findSessionWithCurrentRequirementDoc(String sessionId);
}

package com.agentx.agentxbackend.session.application.port.in;

import com.agentx.agentxbackend.session.application.query.SessionCompletionReadiness;

public interface SessionCompletionReadinessUseCase {

    SessionCompletionReadiness getCompletionReadiness(String sessionId);
}

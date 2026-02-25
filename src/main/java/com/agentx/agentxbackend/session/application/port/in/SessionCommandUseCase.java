package com.agentx.agentxbackend.session.application.port.in;

import com.agentx.agentxbackend.session.domain.model.Session;

public interface SessionCommandUseCase {

    Session createSession(String title);

    Session pauseSession(String sessionId);

    Session resumeSession(String sessionId);

    Session completeSession(String sessionId);
}

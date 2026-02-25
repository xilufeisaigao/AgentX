package com.agentx.agentxbackend.requirement.application.port.in;

import java.util.Optional;

public interface RequirementDocQueryUseCase {

    Optional<RequirementCurrentDoc> findCurrentBySessionId(String sessionId);
}

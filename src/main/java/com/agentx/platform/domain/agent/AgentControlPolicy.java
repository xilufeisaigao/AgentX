package com.agentx.platform.domain.agent;

public record AgentControlPolicy(
    boolean architectSuggested,
    boolean autoPoolEligible,
    boolean manualRegistrationAllowed
) {
}


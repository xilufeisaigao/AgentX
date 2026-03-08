package com.agentx.agentxbackend.planning.application.port.out;

public interface SessionDispatchPolicyPort {

    boolean isSessionDispatchable(String sessionId);
}

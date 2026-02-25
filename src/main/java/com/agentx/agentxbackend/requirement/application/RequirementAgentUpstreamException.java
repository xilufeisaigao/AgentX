package com.agentx.agentxbackend.requirement.application;

public class RequirementAgentUpstreamException extends IllegalStateException {

    public RequirementAgentUpstreamException(String message) {
        super(message);
    }

    public RequirementAgentUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}

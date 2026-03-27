package com.agentx.platform.domain.shared.error;

public final class DomainRuleViolation extends RuntimeException {

    public DomainRuleViolation(String message) {
        super(message);
    }
}

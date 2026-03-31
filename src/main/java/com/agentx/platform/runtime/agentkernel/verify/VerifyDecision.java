package com.agentx.platform.runtime.agentkernel.verify;

import java.util.Objects;

public record VerifyDecision(
        VerifyDecisionType decision,
        String summary,
        String escalationTitle,
        String escalationBody
) {

    public VerifyDecision {
        Objects.requireNonNull(decision, "decision must not be null");
        summary = summary == null || summary.isBlank() ? decision.name() : summary;
    }
}

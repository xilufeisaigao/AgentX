package com.agentx.platform.runtime.evaluation;

import java.util.List;
import java.util.Objects;

public record EvalFinding(
        String code,
        EvalFindingSeverity severity,
        String title,
        String detail,
        List<String> evidenceRefs
) {

    public EvalFinding {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs must not be null"));
    }
}

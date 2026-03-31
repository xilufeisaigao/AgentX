package com.agentx.platform.runtime.context;

import java.util.Objects;

public record ContextCompilationRequest(
        ContextPackType packType,
        ContextScope scope,
        String triggerType
) {

    public ContextCompilationRequest {
        Objects.requireNonNull(packType, "packType must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
    }
}

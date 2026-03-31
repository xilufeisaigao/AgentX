package com.agentx.platform.runtime.agentkernel.model;

import java.util.Objects;

public record StructuredModelResult<T>(
        T value,
        String provider,
        String model,
        String rawResponse
) {

    public StructuredModelResult {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(rawResponse, "rawResponse must not be null");
    }
}

package com.agentx.platform.runtime.tooling;

import java.util.List;
import java.util.Objects;

public record HttpEndpointSpec(
        String endpointId,
        String baseUrl,
        List<String> allowedMethods,
        String notes
) {

    public HttpEndpointSpec {
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        allowedMethods = List.copyOf(Objects.requireNonNull(allowedMethods, "allowedMethods must not be null"));
        notes = notes == null ? "" : notes;
    }
}

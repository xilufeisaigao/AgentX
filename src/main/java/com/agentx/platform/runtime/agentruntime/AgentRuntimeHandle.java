package com.agentx.platform.runtime.agentruntime;

import com.agentx.platform.domain.shared.model.JsonPayload;

import java.util.Objects;

public record AgentRuntimeHandle(
        String endpointRef,
        JsonPayload runtimeMetadataJson
) {

    public AgentRuntimeHandle {
        Objects.requireNonNull(endpointRef, "endpointRef must not be null");
        Objects.requireNonNull(runtimeMetadataJson, "runtimeMetadataJson must not be null");
    }
}

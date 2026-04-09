package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.ExplorationCommandSpec;
import com.agentx.platform.runtime.tooling.HttpEndpointSpec;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CapabilityRuntimeAssembly(
        String image,
        List<String> runtimePacks,
        CompiledToolCatalog toolCatalog,
        Map<String, String> toolEnvironment,
        Map<String, List<String>> allowedCommandCatalog,
        Map<String, ExplorationCommandSpec> explorationCommandCatalog,
        Map<String, HttpEndpointSpec> httpEndpointCatalog,
        List<String> postDeliveryCommandIds,
        List<String> verifyCommandIds,
        List<String> cleanupPaths,
        List<String> reportHints
) {

    public CapabilityRuntimeAssembly {
        Objects.requireNonNull(image, "image must not be null");
        runtimePacks = List.copyOf(Objects.requireNonNull(runtimePacks, "runtimePacks must not be null"));
        Objects.requireNonNull(toolCatalog, "toolCatalog must not be null");
        toolEnvironment = Map.copyOf(Objects.requireNonNull(toolEnvironment, "toolEnvironment must not be null"));
        allowedCommandCatalog = Map.copyOf(Objects.requireNonNull(allowedCommandCatalog, "allowedCommandCatalog must not be null"));
        explorationCommandCatalog = Map.copyOf(Objects.requireNonNull(explorationCommandCatalog, "explorationCommandCatalog must not be null"));
        httpEndpointCatalog = Map.copyOf(Objects.requireNonNull(httpEndpointCatalog, "httpEndpointCatalog must not be null"));
        postDeliveryCommandIds = List.copyOf(Objects.requireNonNull(postDeliveryCommandIds, "postDeliveryCommandIds must not be null"));
        verifyCommandIds = List.copyOf(Objects.requireNonNull(verifyCommandIds, "verifyCommandIds must not be null"));
        cleanupPaths = List.copyOf(Objects.requireNonNull(cleanupPaths, "cleanupPaths must not be null"));
        reportHints = List.copyOf(Objects.requireNonNull(reportHints, "reportHints must not be null"));
    }
}

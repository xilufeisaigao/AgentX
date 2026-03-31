package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.HttpEndpointSpec;
import com.agentx.platform.runtime.tooling.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TaskExecutionContract(
        String image,
        String workingDirectory,
        List<String> command,
        Map<String, String> environment,
        long timeoutSeconds,
        CompiledToolCatalog toolCatalog,
        List<String> runtimePacks,
        Map<String, String> toolEnvironment,
        Map<String, List<String>> allowedCommandCatalog,
        Map<String, HttpEndpointSpec> httpEndpointCatalog,
        List<ToolCall> postDeliveryToolCalls,
        List<ToolCall> verifyToolCalls,
        List<String> writeScopes,
        String markerFile
) {

    public TaskExecutionContract {
        Objects.requireNonNull(image, "image must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        Objects.requireNonNull(toolCatalog, "toolCatalog must not be null");
        runtimePacks = List.copyOf(Objects.requireNonNull(runtimePacks, "runtimePacks must not be null"));
        toolEnvironment = Map.copyOf(Objects.requireNonNull(toolEnvironment, "toolEnvironment must not be null"));
        allowedCommandCatalog = Map.copyOf(Objects.requireNonNull(allowedCommandCatalog, "allowedCommandCatalog must not be null"));
        httpEndpointCatalog = Map.copyOf(Objects.requireNonNull(httpEndpointCatalog, "httpEndpointCatalog must not be null"));
        postDeliveryToolCalls = List.copyOf(Objects.requireNonNull(postDeliveryToolCalls, "postDeliveryToolCalls must not be null"));
        verifyToolCalls = List.copyOf(Objects.requireNonNull(verifyToolCalls, "verifyToolCalls must not be null"));
        writeScopes = List.copyOf(Objects.requireNonNull(writeScopes, "writeScopes must not be null"));
        Objects.requireNonNull(markerFile, "markerFile must not be null");
    }
}

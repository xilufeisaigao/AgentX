package com.agentx.platform.runtime.application.workflow.profile;

import com.agentx.platform.runtime.tooling.ExplorationCommandSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StackProfileManifest(
        Identity identity,
        Planner planner,
        Map<String, String> nodeAgents,
        List<TaskTemplateSpec> taskTemplates,
        PromptRules prompts,
        List<CapabilityRuntimeSpec> capabilityRuntime,
        EvalSpec eval,
        ReportingSpec reporting
) {

    public StackProfileManifest {
        Objects.requireNonNull(identity, "identity must not be null");
        planner = planner == null ? Planner.empty() : planner;
        nodeAgents = copyMap(nodeAgents);
        taskTemplates = List.copyOf(Objects.requireNonNull(taskTemplates, "taskTemplates must not be null"));
        prompts = prompts == null ? PromptRules.empty() : prompts;
        capabilityRuntime = List.copyOf(Objects.requireNonNull(capabilityRuntime, "capabilityRuntime must not be null"));
        eval = eval == null ? EvalSpec.empty() : eval;
        reporting = reporting == null ? ReportingSpec.empty() : reporting;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(String.valueOf(key), value == null ? "" : value));
        return Map.copyOf(normalized);
    }

    private static Map<String, List<String>> copyMultiMap(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(String.valueOf(key), List.copyOf(value == null ? List.of() : value)));
        return Map.copyOf(normalized);
    }

    public record Identity(
            String profileId,
            String displayName,
            String version,
            String workspaceShape,
            String packageManager
    ) {

        public Identity {
            Objects.requireNonNull(profileId, "profileId must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
            Objects.requireNonNull(version, "version must not be null");
            workspaceShape = workspaceShape == null ? "" : workspaceShape;
            packageManager = packageManager == null ? "" : packageManager;
        }
    }

    public record Planner(
            List<String> allowedTaskTemplateIds,
            List<String> rules
    ) {

        public Planner {
            allowedTaskTemplateIds = List.copyOf(allowedTaskTemplateIds == null ? List.of() : allowedTaskTemplateIds);
            rules = List.copyOf(rules == null ? List.of() : rules);
        }

        public static Planner empty() {
            return new Planner(List.of(), List.of());
        }
    }

    public record TaskTemplateSpec(
            String taskTemplateId,
            String capabilityPackId,
            List<String> defaultWriteScopes,
            String deliveryKind,
            List<String> verifyExpectations
    ) {

        public TaskTemplateSpec {
            Objects.requireNonNull(taskTemplateId, "taskTemplateId must not be null");
            Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
            defaultWriteScopes = List.copyOf(defaultWriteScopes == null ? List.of() : defaultWriteScopes);
            deliveryKind = deliveryKind == null ? "" : deliveryKind;
            verifyExpectations = List.copyOf(verifyExpectations == null ? List.of() : verifyExpectations);
        }
    }

    public record PromptRules(
            List<String> architectRules,
            List<String> codingRules,
            List<String> verifyRules
    ) {

        public PromptRules {
            architectRules = List.copyOf(architectRules == null ? List.of() : architectRules);
            codingRules = List.copyOf(codingRules == null ? List.of() : codingRules);
            verifyRules = List.copyOf(verifyRules == null ? List.of() : verifyRules);
        }

        public static PromptRules empty() {
            return new PromptRules(List.of(), List.of(), List.of());
        }
    }

    public record CapabilityRuntimeSpec(
            String capabilityPackId,
            String runtimeImage,
            Map<String, String> environment,
            Map<String, List<String>> allowedCommands,
            List<ExplorationCommandSpec> explorationCommands,
            List<String> postDeliveryCommandIds,
            List<String> verifyCommandIds,
            List<HttpEndpointSpec> httpEndpoints,
            List<String> cleanupPaths,
            List<String> reportHints
    ) {

        public CapabilityRuntimeSpec {
            Objects.requireNonNull(capabilityPackId, "capabilityPackId must not be null");
            Objects.requireNonNull(runtimeImage, "runtimeImage must not be null");
            environment = copyMap(environment);
            allowedCommands = copyMultiMap(allowedCommands);
            explorationCommands = List.copyOf(explorationCommands == null ? List.of() : explorationCommands);
            postDeliveryCommandIds = List.copyOf(postDeliveryCommandIds == null ? List.of() : postDeliveryCommandIds);
            verifyCommandIds = List.copyOf(verifyCommandIds == null ? List.of() : verifyCommandIds);
            httpEndpoints = List.copyOf(httpEndpoints == null ? List.of() : httpEndpoints);
            cleanupPaths = List.copyOf(cleanupPaths == null ? List.of() : cleanupPaths);
            reportHints = List.copyOf(reportHints == null ? List.of() : reportHints);
        }
    }

    public record HttpEndpointSpec(
            String endpointId,
            String baseUrl,
            List<String> allowedMethods,
            String description
    ) {

        public HttpEndpointSpec {
            Objects.requireNonNull(endpointId, "endpointId must not be null");
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            allowedMethods = List.copyOf(allowedMethods == null ? List.of() : allowedMethods);
            description = description == null ? "" : description;
        }
    }

    public record EvalSpec(
            Map<String, List<String>> roleGlobs,
            List<String> requiredArtifactRoles,
            List<String> buildCommandIds,
            List<String> apiCommandIds,
            List<String> integrationCommandIds,
            List<String> frontendConnectivityChecks
    ) {

        public EvalSpec {
            roleGlobs = copyMultiMap(roleGlobs);
            requiredArtifactRoles = List.copyOf(requiredArtifactRoles == null ? List.of() : requiredArtifactRoles);
            buildCommandIds = List.copyOf(buildCommandIds == null ? List.of() : buildCommandIds);
            apiCommandIds = List.copyOf(apiCommandIds == null ? List.of() : apiCommandIds);
            integrationCommandIds = List.copyOf(integrationCommandIds == null ? List.of() : integrationCommandIds);
            frontendConnectivityChecks = List.copyOf(frontendConnectivityChecks == null ? List.of() : frontendConnectivityChecks);
        }

        public static EvalSpec empty() {
            return new EvalSpec(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    public record ReportingSpec(
            List<String> labels,
            List<String> highlightedRoles,
            List<String> enabledDimensions
    ) {

        public ReportingSpec {
            labels = List.copyOf(labels == null ? List.of() : labels);
            highlightedRoles = List.copyOf(highlightedRoles == null ? List.of() : highlightedRoles);
            enabledDimensions = List.copyOf(enabledDimensions == null ? List.of() : enabledDimensions);
        }

        public static ReportingSpec empty() {
            return new ReportingSpec(List.of(), List.of(), List.of());
        }
    }
}

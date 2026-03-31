package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.catalog.model.CapabilitySkillGrant;
import com.agentx.platform.domain.catalog.model.CapabilityToolGrant;
import com.agentx.platform.domain.catalog.model.SkillToolBinding;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.runtime.application.workflow.profile.ActiveStackProfileSnapshot;
import com.agentx.platform.runtime.application.workflow.profile.StackProfileManifest;
import com.agentx.platform.runtime.tooling.CompiledToolCatalog;
import com.agentx.platform.runtime.tooling.HttpEndpointSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CapabilityRuntimeAssembler {

    private final CatalogStore catalogStore;
    private final CapabilityToolCatalogBuilder toolCatalogBuilder;

    public CapabilityRuntimeAssembler(
            CatalogStore catalogStore,
            CapabilityToolCatalogBuilder toolCatalogBuilder
    ) {
        this.catalogStore = catalogStore;
        this.toolCatalogBuilder = toolCatalogBuilder;
    }

    public CapabilityRuntimeAssembly assemble(
            ActiveStackProfileSnapshot activeProfile,
            List<TaskCapabilityRequirement> capabilityRequirements
    ) {
        if (capabilityRequirements == null || capabilityRequirements.isEmpty()) {
            throw new IllegalArgumentException("capability requirements must not be empty");
        }
        List<String> capabilityPackIds = capabilityRequirements.stream()
                .map(TaskCapabilityRequirement::capabilityPackId)
                .distinct()
                .toList();
        String primaryCapabilityPackId = capabilityPackIds.getFirst();

        Set<String> runtimePacks = new LinkedHashSet<>();
        Map<String, String> exposureModes = new LinkedHashMap<>();
        Map<String, StackProfileManifest.CapabilityRuntimeSpec> runtimeSpecs = new LinkedHashMap<>();
        for (String capabilityPackId : capabilityPackIds) {
            StackProfileManifest.CapabilityRuntimeSpec runtimeSpec = activeProfile.findCapabilityRuntime(capabilityPackId)
                    .orElseThrow(() -> new IllegalStateException(
                            "stack profile %s does not define capability runtime for %s".formatted(
                                    activeProfile.profileId(),
                                    capabilityPackId
                            )
                    ));
            runtimeSpecs.put(capabilityPackId, runtimeSpec);
            catalogStore.listCapabilityRuntimeRequirements(capabilityPackId).stream()
                    .map(requirement -> requirement.runtimePackId())
                    .forEach(runtimePacks::add);
            for (CapabilityToolGrant toolGrant : catalogStore.listCapabilityToolGrants(capabilityPackId)) {
                exposureModes.putIfAbsent(toolGrant.toolId(), toolGrant.exposureMode());
            }
            for (CapabilitySkillGrant skillGrant : catalogStore.listCapabilitySkillGrants(capabilityPackId)) {
                for (SkillToolBinding skillToolBinding : catalogStore.listSkillToolBindings(skillGrant.skillId())) {
                    exposureModes.putIfAbsent(skillToolBinding.toolId(), skillToolBinding.invocationMode());
                }
            }
        }

        CompiledToolCatalog toolCatalog = toolCatalogBuilder.build(exposureModes);
        List<String> runtimePackIds = List.copyOf(runtimePacks);
        StackProfileManifest.CapabilityRuntimeSpec primaryRuntime = runtimeSpecs.get(primaryCapabilityPackId);
        return new CapabilityRuntimeAssembly(
                primaryRuntime.runtimeImage(),
                runtimePackIds,
                toolCatalog,
                baseToolEnvironment(capabilityPackIds, runtimePackIds, toolCatalog, runtimeSpecs),
                allowedCommandCatalog(runtimeSpecs, toolCatalog),
                httpEndpointCatalog(runtimeSpecs, toolCatalog),
                primaryRuntime.postDeliveryCommandIds(),
                primaryRuntime.verifyCommandIds(),
                primaryRuntime.cleanupPaths(),
                primaryRuntime.reportHints()
        );
    }

    private Map<String, String> baseToolEnvironment(
            List<String> capabilityPackIds,
            List<String> runtimePackIds,
            CompiledToolCatalog toolCatalog,
            Map<String, StackProfileManifest.CapabilityRuntimeSpec> runtimeSpecs
    ) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("AGENTX_CAPABILITY_PACKS", String.join(",", capabilityPackIds));
        environment.put("AGENTX_RUNTIME_PACKS", String.join(",", runtimePackIds));
        environment.put(
                "AGENTX_TOOL_IDS",
                toolCatalog.entries().stream()
                        .map(entry -> entry.toolId())
                        .distinct()
                        .reduce((left, right) -> left + "," + right)
                        .orElse("")
        );
        runtimeSpecs.values().forEach(runtime -> environment.putAll(runtime.environment()));
        return environment;
    }

    private Map<String, List<String>> allowedCommandCatalog(
            Map<String, StackProfileManifest.CapabilityRuntimeSpec> runtimeSpecs,
            CompiledToolCatalog toolCatalog
    ) {
        Map<String, List<String>> commands = new LinkedHashMap<>();
        if (toolCatalog.find("tool-shell").isEmpty()) {
            return commands;
        }
        runtimeSpecs.values().forEach(runtime -> runtime.allowedCommands().forEach(commands::putIfAbsent));
        return commands;
    }

    private Map<String, HttpEndpointSpec> httpEndpointCatalog(
            Map<String, StackProfileManifest.CapabilityRuntimeSpec> runtimeSpecs,
            CompiledToolCatalog toolCatalog
    ) {
        if (toolCatalog.find("tool-http-client").isEmpty()) {
            return Map.of();
        }
        Map<String, HttpEndpointSpec> endpoints = new LinkedHashMap<>();
        runtimeSpecs.values().forEach(runtime -> runtime.httpEndpoints().forEach(endpoint ->
                endpoints.putIfAbsent(
                        endpoint.endpointId(),
                        new HttpEndpointSpec(
                                endpoint.endpointId(),
                                endpoint.baseUrl(),
                                endpoint.allowedMethods(),
                                endpoint.description()
                        )
                )));
        return Map.copyOf(endpoints);
    }
}

package com.agentx.platform.controlplane.application;

import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AgentDefinitionCommandService {

    private final CatalogStore catalogStore;

    public AgentDefinitionCommandService(CatalogStore catalogStore) {
        this.catalogStore = catalogStore;
    }

    @Transactional
    public AgentDefinitionCommandResult createAgent(
            String agentId,
            String displayName,
            String purpose,
            String runtimeType,
            String model,
            int maxParallelRuns,
            boolean architectSuggested,
            boolean autoPoolEligible,
            boolean manualRegistrationAllowed,
            boolean enabled,
            List<String> capabilityPackIds
    ) {
        if (catalogStore.findAgent(agentId).isPresent()) {
            throw new IllegalStateException("agent already exists: " + agentId);
        }
        List<AgentCapabilityBinding> bindings = validatedBindings(agentId, capabilityPackIds);

        AgentDefinition agentDefinition = new AgentDefinition(
                agentId,
                displayName,
                purpose,
                "MANUAL",
                runtimeType,
                model,
                maxParallelRuns,
                architectSuggested,
                autoPoolEligible,
                manualRegistrationAllowed,
                enabled
        );
        catalogStore.saveAgent(agentDefinition);
        catalogStore.replaceAgentCapabilityBindings(agentId, bindings);
        return AgentDefinitionCommandResult.from(agentDefinition, bindings);
    }

    @Transactional
    public AgentDefinitionCommandResult setAgentEnabled(String agentId, boolean enabled) {
        AgentDefinition currentAgent = catalogStore.findAgent(agentId)
                .orElseThrow(() -> new NoSuchElementException("agent not found: " + agentId));
        AgentDefinition updatedAgent = new AgentDefinition(
                currentAgent.agentId(),
                currentAgent.displayName(),
                currentAgent.purpose(),
                currentAgent.registrationSource(),
                currentAgent.runtimeType(),
                currentAgent.model(),
                currentAgent.maxParallelRuns(),
                currentAgent.architectSuggested(),
                currentAgent.autoPoolEligible(),
                currentAgent.manualRegistrationAllowed(),
                enabled
        );
        catalogStore.saveAgent(updatedAgent);
        return AgentDefinitionCommandResult.from(updatedAgent, catalogStore.listAgentCapabilityBindings(agentId));
    }

    @Transactional
    public AgentDefinitionCommandResult replaceCapabilityBindings(String agentId, List<String> capabilityPackIds) {
        AgentDefinition agentDefinition = catalogStore.findAgent(agentId)
                .orElseThrow(() -> new NoSuchElementException("agent not found: " + agentId));
        List<AgentCapabilityBinding> bindings = validatedBindings(agentId, capabilityPackIds);
        catalogStore.replaceAgentCapabilityBindings(agentId, bindings);
        return AgentDefinitionCommandResult.from(agentDefinition, bindings);
    }

    private List<AgentCapabilityBinding> validatedBindings(String agentId, List<String> capabilityPackIds) {
        if (capabilityPackIds == null || capabilityPackIds.isEmpty()) {
            throw new IllegalArgumentException("capabilityPackIds must not be empty");
        }
        for (String capabilityPackId : capabilityPackIds) {
            if (catalogStore.findCapabilityPack(capabilityPackId).isEmpty()) {
                throw new NoSuchElementException("capability pack not found: " + capabilityPackId);
            }
        }
        return capabilityPackIds.stream()
                .distinct()
                .map(capabilityPackId -> new AgentCapabilityBinding(agentId, capabilityPackId, true))
                .toList();
    }
}

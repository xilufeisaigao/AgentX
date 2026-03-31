package com.agentx.platform.controlplane.application;

import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;

import java.util.List;

public record AgentDefinitionCommandResult(
        String agentId,
        String displayName,
        String runtimeType,
        String model,
        boolean enabled,
        boolean architectSuggested,
        boolean autoPoolEligible,
        boolean manualRegistrationAllowed,
        List<String> capabilityPackIds
) {

    public static AgentDefinitionCommandResult from(AgentDefinition agentDefinition, List<AgentCapabilityBinding> bindings) {
        return new AgentDefinitionCommandResult(
                agentDefinition.agentId(),
                agentDefinition.displayName(),
                agentDefinition.runtimeType(),
                agentDefinition.model(),
                agentDefinition.enabled(),
                agentDefinition.architectSuggested(),
                agentDefinition.autoPoolEligible(),
                agentDefinition.manualRegistrationAllowed(),
                bindings.stream().map(AgentCapabilityBinding::capabilityPackId).toList()
        );
    }
}

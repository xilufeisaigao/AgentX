package com.agentx.platform.domain.catalog.policy;

import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.model.CapabilityPack;
import com.agentx.platform.domain.catalog.model.SkillToolBinding;
import com.agentx.platform.domain.shared.error.DomainRuleViolation;

import java.util.List;

public final class CatalogPolicy {

    private CatalogPolicy() {
    }

    public static void assertAgentDefinitionIsSane(AgentDefinition agentDefinition) {
        if (agentDefinition.maxParallelRuns() <= 0) {
            throw new DomainRuleViolation("agent maxParallelRuns must be positive");
        }
    }

    public static void assertCapabilityPackEnabled(CapabilityPack capabilityPack) {
        if (!capabilityPack.enabled()) {
            throw new DomainRuleViolation("capability pack must be enabled");
        }
    }

    public static void assertToolBindingOrder(List<SkillToolBinding> bindings) {
        int previous = Integer.MIN_VALUE;
        for (SkillToolBinding binding : bindings) {
            if (binding.sortOrder() < previous) {
                throw new DomainRuleViolation("skill tool bindings must be ordered by sortOrder");
            }
            previous = binding.sortOrder();
        }
    }
}

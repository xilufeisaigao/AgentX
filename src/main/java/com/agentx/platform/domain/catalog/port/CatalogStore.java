package com.agentx.platform.domain.catalog.port;

import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.model.CapabilityPack;
import com.agentx.platform.domain.catalog.model.CapabilityRuntimeRequirement;
import com.agentx.platform.domain.catalog.model.CapabilitySkillGrant;
import com.agentx.platform.domain.catalog.model.CapabilityToolGrant;
import com.agentx.platform.domain.catalog.model.SkillToolBinding;

import java.util.List;
import java.util.Optional;

public interface CatalogStore {

    Optional<AgentDefinition> findAgent(String agentId);

    Optional<CapabilityPack> findCapabilityPack(String capabilityPackId);

    List<AgentCapabilityBinding> listAgentCapabilityBindings(String agentId);

    List<CapabilityRuntimeRequirement> listCapabilityRuntimeRequirements(String capabilityPackId);

    List<CapabilityToolGrant> listCapabilityToolGrants(String capabilityPackId);

    List<CapabilitySkillGrant> listCapabilitySkillGrants(String capabilityPackId);

    List<SkillToolBinding> listSkillToolBindings(String skillId);
}

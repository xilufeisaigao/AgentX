package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.catalog.model.AgentCapabilityBinding;
import com.agentx.platform.domain.catalog.model.AgentDefinition;
import com.agentx.platform.domain.catalog.model.CapabilityPack;
import com.agentx.platform.domain.catalog.model.CapabilityRuntimeRequirement;
import com.agentx.platform.domain.catalog.model.CapabilitySkillGrant;
import com.agentx.platform.domain.catalog.model.CapabilityToolGrant;
import com.agentx.platform.domain.catalog.model.SkillToolBinding;
import com.agentx.platform.domain.catalog.port.CatalogStore;
import com.agentx.platform.runtime.persistence.mybatis.mapper.CatalogMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MybatisCatalogRepository implements CatalogStore {

    private final CatalogMapper catalogMapper;

    public MybatisCatalogRepository(CatalogMapper catalogMapper) {
        this.catalogMapper = catalogMapper;
    }

    @Override
    public Optional<AgentDefinition> findAgent(String agentId) {
        Map<String, Object> row = catalogMapper.findAgentRow(agentId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(new AgentDefinition(
                MybatisRowReader.string(row, "agentId"),
                MybatisRowReader.string(row, "displayName"),
                MybatisRowReader.string(row, "purpose"),
                MybatisRowReader.string(row, "registrationSource"),
                MybatisRowReader.string(row, "runtimeType"),
                MybatisRowReader.string(row, "model"),
                MybatisRowReader.integer(row, "maxParallelRuns"),
                MybatisRowReader.bool(row, "architectSuggested"),
                MybatisRowReader.bool(row, "autoPoolEligible"),
                MybatisRowReader.bool(row, "manualRegistrationAllowed"),
                MybatisRowReader.bool(row, "enabled")
        ));
    }

    @Override
    public Optional<CapabilityPack> findCapabilityPack(String capabilityPackId) {
        Map<String, Object> row = catalogMapper.findCapabilityPackRow(capabilityPackId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityPack(
                MybatisRowReader.string(row, "capabilityPackId"),
                MybatisRowReader.string(row, "displayName"),
                MybatisRowReader.string(row, "capabilityKind"),
                MybatisRowReader.string(row, "granularity"),
                MybatisRowReader.string(row, "purpose"),
                MybatisRowReader.nullableString(row, "description"),
                MybatisRowReader.bool(row, "enabled")
        ));
    }

    @Override
    public List<AgentDefinition> listAgentsByCapability(String capabilityPackId) {
        return catalogMapper.listAgentRowsByCapability(capabilityPackId).stream()
                .map(row -> new AgentDefinition(
                        MybatisRowReader.string(row, "agentId"),
                        MybatisRowReader.string(row, "displayName"),
                        MybatisRowReader.string(row, "purpose"),
                        MybatisRowReader.string(row, "registrationSource"),
                        MybatisRowReader.string(row, "runtimeType"),
                        MybatisRowReader.string(row, "model"),
                        MybatisRowReader.integer(row, "maxParallelRuns"),
                        MybatisRowReader.bool(row, "architectSuggested"),
                        MybatisRowReader.bool(row, "autoPoolEligible"),
                        MybatisRowReader.bool(row, "manualRegistrationAllowed"),
                        MybatisRowReader.bool(row, "enabled")
                ))
                .toList();
    }

    @Override
    public List<AgentCapabilityBinding> listAgentCapabilityBindings(String agentId) {
        return catalogMapper.listAgentCapabilityBindingRows(agentId).stream()
                .map(row -> new AgentCapabilityBinding(
                        MybatisRowReader.string(row, "agentId"),
                        MybatisRowReader.string(row, "capabilityPackId"),
                        MybatisRowReader.bool(row, "required")
                ))
                .toList();
    }

    @Override
    public List<CapabilityRuntimeRequirement> listCapabilityRuntimeRequirements(String capabilityPackId) {
        return catalogMapper.listCapabilityRuntimeRequirementRows(capabilityPackId).stream()
                .map(row -> new CapabilityRuntimeRequirement(
                        MybatisRowReader.string(row, "capabilityPackId"),
                        MybatisRowReader.string(row, "runtimePackId"),
                        MybatisRowReader.bool(row, "required"),
                        MybatisRowReader.nullableString(row, "purpose")
                ))
                .toList();
    }

    @Override
    public List<CapabilityToolGrant> listCapabilityToolGrants(String capabilityPackId) {
        return catalogMapper.listCapabilityToolGrantRows(capabilityPackId).stream()
                .map(row -> new CapabilityToolGrant(
                        MybatisRowReader.string(row, "capabilityPackId"),
                        MybatisRowReader.string(row, "toolId"),
                        MybatisRowReader.bool(row, "required"),
                        MybatisRowReader.string(row, "exposureMode")
                ))
                .toList();
    }

    @Override
    public List<CapabilitySkillGrant> listCapabilitySkillGrants(String capabilityPackId) {
        return catalogMapper.listCapabilitySkillGrantRows(capabilityPackId).stream()
                .map(row -> new CapabilitySkillGrant(
                        MybatisRowReader.string(row, "capabilityPackId"),
                        MybatisRowReader.string(row, "skillId"),
                        MybatisRowReader.bool(row, "required"),
                        MybatisRowReader.string(row, "roleInPack")
                ))
                .toList();
    }

    @Override
    public List<SkillToolBinding> listSkillToolBindings(String skillId) {
        return catalogMapper.listSkillToolBindingRows(skillId).stream()
                .map(row -> new SkillToolBinding(
                        MybatisRowReader.string(row, "skillId"),
                        MybatisRowReader.string(row, "toolId"),
                        MybatisRowReader.bool(row, "required"),
                        MybatisRowReader.string(row, "invocationMode"),
                        MybatisRowReader.integer(row, "sortOrder")
                ))
                .toList();
    }

    @Override
    public void saveAgent(AgentDefinition agentDefinition) {
        catalogMapper.upsertAgentDefinition(agentDefinition);
    }

    @Override
    public void replaceAgentCapabilityBindings(String agentId, List<AgentCapabilityBinding> bindings) {
        catalogMapper.deleteAgentCapabilityBindings(agentId);
        for (AgentCapabilityBinding binding : bindings) {
            catalogMapper.insertAgentCapabilityBinding(binding.agentId(), binding.capabilityPackId(), binding.required());
        }
    }
}

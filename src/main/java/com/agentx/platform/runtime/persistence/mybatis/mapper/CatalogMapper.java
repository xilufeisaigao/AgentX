package com.agentx.platform.runtime.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CatalogMapper {

    @Select("""
            select
              agent_id as agentId,
              display_name as displayName,
              purpose,
              registration_source as registrationSource,
              runtime_type as runtimeType,
              model,
              max_parallel_runs as maxParallelRuns,
              architect_suggested as architectSuggested,
              auto_pool_eligible as autoPoolEligible,
              manual_registration_allowed as manualRegistrationAllowed,
              enabled
            from agent_definitions
            where agent_id = #{agentId}
            """)
    Map<String, Object> findAgentRow(@Param("agentId") String agentId);

    @Select("""
            select
              capability_pack_id as capabilityPackId,
              display_name as displayName,
              capability_kind as capabilityKind,
              granularity,
              purpose,
              description,
              enabled
            from capability_packs
            where capability_pack_id = #{capabilityPackId}
            """)
    Map<String, Object> findCapabilityPackRow(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select distinct
              ad.agent_id as agentId,
              ad.display_name as displayName,
              ad.purpose as purpose,
              ad.registration_source as registrationSource,
              ad.runtime_type as runtimeType,
              ad.model as model,
              ad.max_parallel_runs as maxParallelRuns,
              ad.architect_suggested as architectSuggested,
              ad.auto_pool_eligible as autoPoolEligible,
              ad.manual_registration_allowed as manualRegistrationAllowed,
              ad.enabled as enabled
            from agent_definitions ad
            join agent_definition_capability_packs adcp on adcp.agent_id = ad.agent_id
            where adcp.capability_pack_id = #{capabilityPackId}
              and ad.enabled = true
            order by ad.agent_id
            """)
    List<Map<String, Object>> listAgentRowsByCapability(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select
              agent_id as agentId,
              capability_pack_id as capabilityPackId,
              required_flag as required
            from agent_definition_capability_packs
            where agent_id = #{agentId}
            order by capability_pack_id
            """)
    List<Map<String, Object>> listAgentCapabilityBindingRows(@Param("agentId") String agentId);

    @Select("""
            select
              capability_pack_id as capabilityPackId,
              runtime_pack_id as runtimePackId,
              required_flag as required,
              purpose
            from capability_pack_runtime_packs
            where capability_pack_id = #{capabilityPackId}
            order by runtime_pack_id
            """)
    List<Map<String, Object>> listCapabilityRuntimeRequirementRows(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select
              capability_pack_id as capabilityPackId,
              tool_id as toolId,
              required_flag as required,
              exposure_mode as exposureMode
            from capability_pack_tools
            where capability_pack_id = #{capabilityPackId}
            order by tool_id
            """)
    List<Map<String, Object>> listCapabilityToolGrantRows(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select
              capability_pack_id as capabilityPackId,
              skill_id as skillId,
              required_flag as required,
              role_in_pack as roleInPack
            from capability_pack_skills
            where capability_pack_id = #{capabilityPackId}
            order by skill_id
            """)
    List<Map<String, Object>> listCapabilitySkillGrantRows(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select
              skill_id as skillId,
              tool_id as toolId,
              required_flag as required,
              invocation_mode as invocationMode,
              sort_order as sortOrder
            from skill_tool_bindings
            where skill_id = #{skillId}
            order by sort_order, tool_id
            """)
    List<Map<String, Object>> listSkillToolBindingRows(@Param("skillId") String skillId);

    @Insert("""
            insert into agent_definitions (
              agent_id,
              display_name,
              purpose,
              registration_source,
              system_prompt_text,
              runtime_type,
              model,
              max_parallel_runs,
              architect_suggested,
              auto_pool_eligible,
              manual_registration_allowed,
              enabled
            ) values (
              #{agent.agentId},
              #{agent.displayName},
              #{agent.purpose},
              #{agent.registrationSource},
              null,
              #{agent.runtimeType},
              #{agent.model},
              #{agent.maxParallelRuns},
              #{agent.architectSuggested},
              #{agent.autoPoolEligible},
              #{agent.manualRegistrationAllowed},
              #{agent.enabled}
            )
            on duplicate key update
              display_name = values(display_name),
              purpose = values(purpose),
              registration_source = values(registration_source),
              runtime_type = values(runtime_type),
              model = values(model),
              max_parallel_runs = values(max_parallel_runs),
              architect_suggested = values(architect_suggested),
              auto_pool_eligible = values(auto_pool_eligible),
              manual_registration_allowed = values(manual_registration_allowed),
              enabled = values(enabled)
            """)
    void upsertAgentDefinition(@Param("agent") Object agentDefinition);

    @Delete("""
            delete from agent_definition_capability_packs
            where agent_id = #{agentId}
            """)
    void deleteAgentCapabilityBindings(@Param("agentId") String agentId);

    @Insert("""
            insert into agent_definition_capability_packs (
              agent_id,
              capability_pack_id,
              required_flag
            ) values (
              #{agentId},
              #{capabilityPackId},
              #{required}
            )
            """)
    void insertAgentCapabilityBinding(
            @Param("agentId") String agentId,
            @Param("capabilityPackId") String capabilityPackId,
            @Param("required") boolean required
    );
}

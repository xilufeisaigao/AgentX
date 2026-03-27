package com.agentx.platform.runtime.persistence.mybatis.mapper;

import com.agentx.platform.domain.flow.model.WorkflowNodeBinding;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface FlowMapper {

    @Select("""
            select
              workflow_template_id as workflowTemplateId,
              display_name as displayName,
              description,
              mutability,
              registration_policy as registrationPolicy,
              is_system_builtin as systemBuiltin,
              enabled,
              version
            from workflow_templates
            where workflow_template_id = #{workflowTemplateId}
            """)
    Map<String, Object> findTemplateRow(@Param("workflowTemplateId") String workflowTemplateId);

    @Select("""
            select
              workflow_template_id as workflowTemplateId,
              node_id as nodeId,
              display_name as displayName,
              node_kind as nodeKind,
              sequence_no as sequenceNo,
              default_agent_id as defaultAgentId,
              agent_binding_configurable as agentBindingConfigurable
            from workflow_template_nodes
            where workflow_template_id = #{workflowTemplateId}
            order by sequence_no
            """)
    List<Map<String, Object>> listTemplateNodeRows(@Param("workflowTemplateId") String workflowTemplateId);

    @Select("""
            select
              workflow_run_id as workflowRunId,
              workflow_template_id as workflowTemplateId,
              title,
              status,
              entry_mode as entryMode,
              auto_agent_mode as autoAgentMode,
              created_by_actor_type as createdByActorType,
              created_by_actor_id as createdByActorId
            from workflow_runs
            where workflow_run_id = #{workflowRunId}
            """)
    Map<String, Object> findRunRow(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              binding_id as bindingId,
              workflow_run_id as workflowRunId,
              node_id as nodeId,
              binding_mode as bindingMode,
              selected_agent_id as selectedAgentId,
              locked_by_user as lockedByUser
            from workflow_run_node_bindings
            where workflow_run_id = #{workflowRunId}
            order by node_id
            """)
    List<Map<String, Object>> listNodeBindingRows(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              node_run_id as nodeRunId,
              workflow_run_id as workflowRunId,
              node_id as nodeId,
              selected_agent_id as selectedAgentId,
              agent_instance_id as agentInstanceId,
              status
            from workflow_node_runs
            where workflow_run_id = #{workflowRunId}
            order by started_at, node_run_id
            """)
    List<Map<String, Object>> listNodeRunRows(@Param("workflowRunId") String workflowRunId);

    @Insert("""
            insert into workflow_runs (
              workflow_run_id,
              workflow_template_id,
              title,
              status,
              entry_mode,
              auto_agent_mode,
              created_by_actor_type,
              created_by_actor_id
            ) values (
              #{run.workflowRunId},
              #{run.workflowTemplateId},
              #{run.title},
              #{run.status},
              #{run.entryMode},
              #{run.autoAgentMode},
              #{createdByActorType},
              #{createdByActorId}
            )
            on duplicate key update
              workflow_template_id = values(workflow_template_id),
              title = values(title),
              status = values(status),
              entry_mode = values(entry_mode),
              auto_agent_mode = values(auto_agent_mode),
              created_by_actor_type = values(created_by_actor_type),
              created_by_actor_id = values(created_by_actor_id)
            """)
    void upsertRun(
            @Param("run") WorkflowRun run,
            @Param("createdByActorType") String createdByActorType,
            @Param("createdByActorId") String createdByActorId
    );

    @Insert("""
            insert into workflow_run_node_bindings (
              binding_id,
              workflow_run_id,
              node_id,
              binding_mode,
              selected_agent_id,
              rationale,
              locked_by_user
            ) values (
              #{binding.bindingId},
              #{binding.workflowRunId},
              #{binding.nodeId},
              #{binding.bindingMode},
              #{binding.selectedAgentId},
              #{rationale},
              #{binding.lockedByUser}
            )
            on duplicate key update
              binding_mode = values(binding_mode),
              selected_agent_id = values(selected_agent_id),
              rationale = values(rationale),
              locked_by_user = values(locked_by_user)
            """)
    void upsertNodeBinding(@Param("binding") WorkflowNodeBinding binding, @Param("rationale") String rationale);
}

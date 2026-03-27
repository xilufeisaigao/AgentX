package com.agentx.platform.runtime.persistence.mybatis.mapper;

import com.agentx.platform.domain.planning.model.TaskCapabilityRequirement;
import com.agentx.platform.domain.planning.model.TaskDependency;
import com.agentx.platform.domain.planning.model.WorkModule;
import com.agentx.platform.domain.planning.model.WorkTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface PlanningMapper {

    @Select("""
            select
              module_id as moduleId,
              workflow_run_id as workflowRunId,
              name,
              description
            from work_modules
            where workflow_run_id = #{workflowRunId}
            order by module_id
            """)
    List<Map<String, Object>> listModuleRows(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              wt.task_id as taskId,
              wt.module_id as moduleId,
              wt.title as title,
              wt.objective as objective,
              wt.task_template_id as taskTemplateId,
              wt.status as status,
              wt.write_scope_json as writeScopesJson,
              wt.origin_ticket_id as originTicketId,
              wt.created_by_actor_type as createdByActorType,
              wt.created_by_actor_id as createdByActorId
            from work_tasks wt
            join work_modules wm on wm.module_id = wt.module_id
            where wm.workflow_run_id = #{workflowRunId}
            order by wt.created_at, wt.task_id
            """)
    List<Map<String, Object>> listTaskRows(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              task_id as taskId,
              module_id as moduleId,
              title,
              objective,
              task_template_id as taskTemplateId,
              status,
              write_scope_json as writeScopesJson,
              origin_ticket_id as originTicketId,
              created_by_actor_type as createdByActorType,
              created_by_actor_id as createdByActorId
            from work_tasks
            where task_id = #{taskId}
            """)
    Map<String, Object> findTaskRow(@Param("taskId") String taskId);

    @Select("""
            select
              d.task_id as taskId,
              d.depends_on_task_id as dependsOnTaskId,
              d.required_upstream_status as requiredUpstreamStatus
            from work_task_dependencies d
            join work_tasks wt on wt.task_id = d.task_id
            join work_modules wm on wm.module_id = wt.module_id
            where wm.workflow_run_id = #{workflowRunId}
            order by d.task_id, d.depends_on_task_id
            """)
    List<Map<String, Object>> listDependencyRows(@Param("workflowRunId") String workflowRunId);

    @Select("""
            select
              task_id as taskId,
              capability_pack_id as capabilityPackId,
              required_flag as required,
              role_in_task as roleInTask
            from work_task_capability_requirements
            where task_id = #{taskId}
            order by capability_pack_id
            """)
    List<Map<String, Object>> listCapabilityRequirementRows(@Param("taskId") String taskId);

    @Insert("""
            insert into work_modules (
              module_id,
              workflow_run_id,
              name,
              description
            ) values (
              #{module.moduleId},
              #{module.workflowRunId},
              #{module.name},
              #{module.description}
            )
            on duplicate key update
              workflow_run_id = values(workflow_run_id),
              name = values(name),
              description = values(description)
            """)
    void upsertModule(@Param("module") WorkModule module);

    @Insert("""
            insert into work_tasks (
              task_id,
              module_id,
              title,
              objective,
              task_template_id,
              status,
              write_scope_json,
              origin_ticket_id,
              created_by_actor_type,
              created_by_actor_id
            ) values (
              #{task.taskId},
              #{task.moduleId},
              #{task.title},
              #{task.objective},
              #{task.taskTemplateId},
              #{task.status},
              #{task.writeScopes, typeHandler=com.agentx.platform.runtime.persistence.mybatis.typehandler.WriteScopeListJsonTypeHandler},
              #{task.originTicketId},
              #{createdByActorType},
              #{createdByActorId}
            )
            on duplicate key update
              module_id = values(module_id),
              title = values(title),
              objective = values(objective),
              task_template_id = values(task_template_id),
              status = values(status),
              write_scope_json = values(write_scope_json),
              origin_ticket_id = values(origin_ticket_id),
              created_by_actor_type = values(created_by_actor_type),
              created_by_actor_id = values(created_by_actor_id)
            """)
    void upsertTask(
            @Param("task") WorkTask task,
            @Param("createdByActorType") String createdByActorType,
            @Param("createdByActorId") String createdByActorId
    );

    @Insert("""
            insert into work_task_dependencies (
              task_id,
              depends_on_task_id,
              required_upstream_status
            ) values (
              #{dependency.taskId},
              #{dependency.dependsOnTaskId},
              #{dependency.requiredUpstreamStatus}
            )
            on duplicate key update
              required_upstream_status = values(required_upstream_status)
            """)
    void upsertDependency(@Param("dependency") TaskDependency dependency);

    @Insert("""
            insert into work_task_capability_requirements (
              task_id,
              capability_pack_id,
              required_flag,
              role_in_task
            ) values (
              #{requirement.taskId},
              #{requirement.capabilityPackId},
              #{requirement.required},
              #{requirement.roleInTask}
            )
            on duplicate key update
              required_flag = values(required_flag),
              role_in_task = values(role_in_task)
            """)
    void upsertCapabilityRequirement(@Param("requirement") TaskCapabilityRequirement requirement);
}

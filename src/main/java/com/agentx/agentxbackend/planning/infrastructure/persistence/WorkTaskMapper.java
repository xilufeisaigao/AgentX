package com.agentx.agentxbackend.planning.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WorkTaskMapper {

    @Insert("""
        insert into work_tasks (
            task_id,
            module_id,
            title,
            task_template_id,
            status,
            required_toolpacks_json,
            active_run_id,
            created_by_role,
            created_at,
            updated_at
        ) values (
            #{row.taskId},
            #{row.moduleId},
            #{row.title},
            #{row.taskTemplateId},
            #{row.status},
            #{row.requiredToolpacksJson},
            #{row.activeRunId},
            #{row.createdByRole},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") WorkTaskRow row);

    @Select("""
        select
            task_id,
            module_id,
            title,
            task_template_id,
            status,
            required_toolpacks_json,
            active_run_id,
            created_by_role,
            created_at,
            updated_at
        from work_tasks
        where task_id = #{taskId}
        """)
    WorkTaskRow findById(@Param("taskId") String taskId);

    @Update("""
        update work_tasks
        set
            title = #{row.title},
            status = #{row.status},
            required_toolpacks_json = #{row.requiredToolpacksJson},
            active_run_id = #{row.activeRunId},
            updated_at = #{row.updatedAt}
        where task_id = #{row.taskId}
        """)
    int update(@Param("row") WorkTaskRow row);

    @Select("""
        select
            task_id,
            module_id,
            title,
            task_template_id,
            status,
            required_toolpacks_json,
            active_run_id,
            created_by_role,
            created_at,
            updated_at
        from work_tasks
        where status = #{status}
        order by created_at asc
        limit #{limit}
        """)
    List<WorkTaskRow> findByStatus(
        @Param("status") String status,
        @Param("limit") int limit
    );

    @Select("""
        select
            task_id,
            module_id,
            title,
            task_template_id,
            status,
            required_toolpacks_json,
            active_run_id,
            created_by_role,
            created_at,
            updated_at
        from work_tasks
        where status = #{status}
        order by updated_at desc, created_at desc
        limit #{limit} offset #{offset}
        """)
    List<WorkTaskRow> findByStatusPaged(
        @Param("status") String status,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    @Update("""
        update work_tasks t
        left join work_task_dependencies d on d.task_id = t.task_id
        left join work_tasks p on p.task_id = d.depends_on_task_id
            and p.status <> d.required_upstream_status
        set
            t.status = 'ASSIGNED',
            t.active_run_id = #{runId},
            t.updated_at = #{updatedAt}
        where t.task_id = #{taskId}
          and t.status = 'READY_FOR_ASSIGN'
          and t.active_run_id is null
          and p.task_id is null
        """)
    int claimIfReady(
        @Param("taskId") String taskId,
        @Param("runId") String runId,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );

    @Select("""
        select count(1)
        from work_tasks
        where task_template_id = #{taskTemplateId}
          and status <> 'DONE'
        """)
    int countNonDoneByTemplateId(@Param("taskTemplateId") String taskTemplateId);

    @Select("""
        select count(1)
        from work_tasks t
        join work_modules m on m.module_id = t.module_id
        where m.session_id = #{sessionId}
          and t.task_template_id = #{taskTemplateId}
          and t.status <> 'DONE'
        """)
    int countNonDoneBySessionIdAndTemplateId(
        @Param("sessionId") String sessionId,
        @Param("taskTemplateId") String taskTemplateId
    );


    @Select("""
        select count(1)
        from work_tasks t
        join work_modules m on m.module_id = t.module_id
        where m.session_id = #{sessionId}
          and t.status <> 'DONE'
        """)
    int countNonDoneBySessionId(@Param("sessionId") String sessionId);
}

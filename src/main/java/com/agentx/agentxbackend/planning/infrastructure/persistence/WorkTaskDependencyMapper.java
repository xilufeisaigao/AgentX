package com.agentx.agentxbackend.planning.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkTaskDependencyMapper {

    @Insert("""
        insert ignore into work_task_dependencies (
            task_id,
            depends_on_task_id,
            required_upstream_status,
            created_at
        ) values (
            #{row.taskId},
            #{row.dependsOnTaskId},
            #{row.requiredUpstreamStatus},
            #{row.createdAt}
        )
        """)
    int insertIgnore(@Param("row") WorkTaskDependencyRow row);

    @Select("""
        select count(*)
        from work_task_dependencies
        where task_id = #{taskId}
          and depends_on_task_id = #{dependsOnTaskId}
        """)
    int countByTaskAndDependency(
        @Param("taskId") String taskId,
        @Param("dependsOnTaskId") String dependsOnTaskId
    );

    @Select("""
        select
            task_id,
            depends_on_task_id,
            required_upstream_status,
            created_at
        from work_task_dependencies
        where task_id = #{taskId}
        order by created_at asc
        """)
    List<WorkTaskDependencyRow> findByTaskId(@Param("taskId") String taskId);

    @Select("""
        select
            task_id,
            depends_on_task_id,
            required_upstream_status,
            created_at
        from work_task_dependencies
        where depends_on_task_id = #{dependsOnTaskId}
        order by created_at asc
        """)
    List<WorkTaskDependencyRow> findByDependsOnTaskId(@Param("dependsOnTaskId") String dependsOnTaskId);
}

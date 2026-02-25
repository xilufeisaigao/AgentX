package com.agentx.agentxbackend.workspace.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GitWorkspaceMapper {

    @Insert("""
        insert into git_workspaces (
            run_id,
            status,
            created_at,
            updated_at
        ) values (
            #{row.runId},
            #{row.status},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") GitWorkspaceRow row);

    @Select("""
        select
            run_id,
            status,
            created_at,
            updated_at
        from git_workspaces
        where run_id = #{runId}
        """)
    GitWorkspaceRow findByRunId(@Param("runId") String runId);

    @Update("""
        update git_workspaces
        set
            status = #{status},
            updated_at = #{updatedAt}
        where run_id = #{runId}
        """)
    int updateStatus(
        @Param("runId") String runId,
        @Param("status") String status,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );
}

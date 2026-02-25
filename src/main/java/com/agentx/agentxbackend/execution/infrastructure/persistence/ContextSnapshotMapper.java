package com.agentx.agentxbackend.execution.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ContextSnapshotMapper {

    @Select("""
        select
            snapshot_id,
            task_context_ref,
            task_skill_ref
        from task_context_snapshots
        where task_id = #{taskId}
          and run_kind = #{runKind}
          and status = 'READY'
        order by compiled_at desc, created_at desc, snapshot_id desc
        limit 1
        """)
    ContextSnapshotRow findLatestReadyByTaskAndRunKind(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind
    );
}

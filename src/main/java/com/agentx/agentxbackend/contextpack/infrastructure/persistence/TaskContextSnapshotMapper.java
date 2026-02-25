package com.agentx.agentxbackend.contextpack.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface TaskContextSnapshotMapper {

    @Insert("""
        insert into task_context_snapshots (
            snapshot_id,
            task_id,
            run_kind,
            status,
            trigger_type,
            source_fingerprint,
            task_context_ref,
            task_skill_ref,
            error_code,
            error_message,
            compiled_at,
            retained_until,
            created_at,
            updated_at
        ) values (
            #{row.snapshotId},
            #{row.taskId},
            #{row.runKind},
            #{row.status},
            #{row.triggerType},
            #{row.sourceFingerprint},
            #{row.taskContextRef},
            #{row.taskSkillRef},
            #{row.errorCode},
            #{row.errorMessage},
            #{row.compiledAt},
            #{row.retainedUntil},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") TaskContextSnapshotRow row);

    @Select("""
        select
            snapshot_id,
            task_id,
            run_kind,
            status,
            trigger_type,
            source_fingerprint,
            task_context_ref,
            task_skill_ref,
            error_code,
            error_message,
            compiled_at,
            retained_until,
            created_at,
            updated_at
        from task_context_snapshots
        where task_id = #{taskId}
          and run_kind = #{runKind}
        order by
          created_at desc,
          case status
            when 'READY' then 0
            when 'COMPILING' then 1
            when 'PENDING' then 2
            when 'STALE' then 3
            when 'FAILED' then 4
            else 9
          end asc,
          updated_at desc,
          snapshot_id desc
        limit 1
        """)
    TaskContextSnapshotRow findLatestByTaskAndRunKind(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind
    );

    @Select("""
        select
            snapshot_id,
            task_id,
            run_kind,
            status,
            trigger_type,
            source_fingerprint,
            task_context_ref,
            task_skill_ref,
            error_code,
            error_message,
            compiled_at,
            retained_until,
            created_at,
            updated_at
        from task_context_snapshots
        where task_id = #{taskId}
          and run_kind = #{runKind}
          and status = 'READY'
          and source_fingerprint = #{sourceFingerprint}
        order by compiled_at desc, created_at desc
        limit 1
        """)
    TaskContextSnapshotRow findLatestReadyByFingerprint(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind,
        @Param("sourceFingerprint") String sourceFingerprint
    );

    @Select("""
        select
            snapshot_id,
            task_id,
            run_kind,
            status,
            trigger_type,
            source_fingerprint,
            task_context_ref,
            task_skill_ref,
            error_code,
            error_message,
            compiled_at,
            retained_until,
            created_at,
            updated_at
        from task_context_snapshots
        where task_id = #{taskId}
        order by
          created_at desc,
          case status
            when 'READY' then 0
            when 'COMPILING' then 1
            when 'PENDING' then 2
            when 'STALE' then 3
            when 'FAILED' then 4
            else 9
          end asc,
          updated_at desc,
          snapshot_id desc
        limit #{limit}
        """)
    List<TaskContextSnapshotRow> findLatestByTaskId(
        @Param("taskId") String taskId,
        @Param("limit") int limit
    );

    @Update("""
        update task_context_snapshots
        set
            status = 'STALE',
            updated_at = #{updatedAt}
        where task_id = #{taskId}
          and run_kind = #{runKind}
          and status = 'READY'
        """)
    int markReadyAsStale(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind,
        @Param("updatedAt") Timestamp updatedAt
    );

    @Update("""
        update task_context_snapshots
        set
            status = #{nextStatus},
            updated_at = #{updatedAt}
        where snapshot_id = #{snapshotId}
          and status = #{expectedStatus}
        """)
    int transitionStatus(
        @Param("snapshotId") String snapshotId,
        @Param("expectedStatus") String expectedStatus,
        @Param("nextStatus") String nextStatus,
        @Param("updatedAt") Timestamp updatedAt
    );

    @Update("""
        update task_context_snapshots
        set
            status = 'READY',
            task_context_ref = #{taskContextRef},
            task_skill_ref = #{taskSkillRef},
            error_code = null,
            error_message = null,
            compiled_at = #{compiledAt},
            updated_at = #{updatedAt}
        where snapshot_id = #{snapshotId}
          and status = 'COMPILING'
        """)
    int markReady(
        @Param("snapshotId") String snapshotId,
        @Param("taskContextRef") String taskContextRef,
        @Param("taskSkillRef") String taskSkillRef,
        @Param("compiledAt") Timestamp compiledAt,
        @Param("updatedAt") Timestamp updatedAt
    );

    @Update("""
        update task_context_snapshots
        set
            status = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            updated_at = #{updatedAt}
        where snapshot_id = #{snapshotId}
          and status = 'COMPILING'
        """)
    int markFailed(
        @Param("snapshotId") String snapshotId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("updatedAt") Timestamp updatedAt
    );
}

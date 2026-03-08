package com.agentx.agentxbackend.execution.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskRunMapper {

    @Insert("""
        insert into task_runs (
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        ) values (
            #{row.runId},
            #{row.taskId},
            #{row.workerId},
            #{row.status},
            #{row.runKind},
            #{row.contextSnapshotId},
            #{row.leaseUntil},
            #{row.lastHeartbeatAt},
            #{row.startedAt},
            #{row.finishedAt},
            #{row.taskSkillRef},
            #{row.toolpacksSnapshotJson},
            #{row.baseCommit},
            #{row.branchName},
            #{row.worktreePath},
            #{row.createdAt},
            #{row.updatedAt}
        )
        """)
    int insert(@Param("row") TaskRunRow row);

    @Select("""
        select
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        from task_runs
        where run_id = #{runId}
        """)
    TaskRunRow findById(@Param("runId") String runId);

    @Update("""
        update task_runs
        set
            status = #{row.status},
            lease_until = #{row.leaseUntil},
            last_heartbeat_at = #{row.lastHeartbeatAt},
            finished_at = #{row.finishedAt},
            updated_at = #{row.updatedAt}
        where run_id = #{row.runId}
        """)
    int update(@Param("row") TaskRunRow row);

    @Select("""
        select count(1)
        from task_runs
        where status in ('RUNNING', 'WAITING_FOREMAN')
        """)
    int countActiveRuns();

    @Select("""
        select distinct worker_id
        from task_runs
        where status in ('RUNNING', 'WAITING_FOREMAN')
        order by worker_id asc
        limit #{limit}
        """)
    List<String> findActiveWorkerIds(@Param("limit") int limit);

    @Select("""
        select
            worker_id,
            count(1) as total_runs,
            max(coalesce(finished_at, last_heartbeat_at, started_at, created_at)) as last_activity_at
        from task_runs
        where worker_id = #{workerId}
        group by worker_id
        """)
    TaskRunWorkerStatsRow findWorkerStats(@Param("workerId") String workerId);

    @Select("""
        select
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        from task_runs
        where worker_id = #{workerId}
          and run_kind = 'VERIFY'
          and status = 'RUNNING'
        order by created_at asc
        limit 1
        """)
    TaskRunRow findOldestRunningVerifyRunByWorker(@Param("workerId") String workerId);

    @Select("""
        select
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        from task_runs
        where task_id = #{taskId}
          and run_kind = 'VERIFY'
          and base_commit = #{baseCommit}
        order by created_at desc
        limit 1
        """)
    TaskRunRow findLatestVerifyRunByTaskAndBaseCommit(
        @Param("taskId") String taskId,
        @Param("baseCommit") String baseCommit
    );

    @Select("""
        select count(1)
        from task_runs
        where task_id = #{taskId}
          and run_kind = 'VERIFY'
          and base_commit = #{baseCommit}
        """)
    int countVerifyRunsByTaskAndBaseCommit(
        @Param("taskId") String taskId,
        @Param("baseCommit") String baseCommit
    );

    @Select("""
        select
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        from task_runs
        where task_id = #{taskId}
          and run_kind = #{runKind}
        order by created_at desc
        limit 1
        """)
    TaskRunRow findLatestRunByTaskAndKind(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind
    );

    @Select("""
        select count(1)
        from task_runs
        where task_id = #{taskId}
          and run_kind = #{runKind}
          and status in ('RUNNING', 'WAITING_FOREMAN')
        """)
    int countActiveRunsByTaskAndKind(
        @Param("taskId") String taskId,
        @Param("runKind") String runKind
    );

    

    @Select("""
        select count(1)
        from task_runs r
        join work_tasks t on t.task_id = r.task_id
        join work_modules m on m.module_id = t.module_id
        where m.session_id = #{sessionId}
          and r.status in ('RUNNING', 'WAITING_FOREMAN')
        """)
    int countActiveRunsBySessionId(@Param("sessionId") String sessionId);

@Select("""
        select
            run_id,
            task_id,
            worker_id,
            status,
            run_kind,
            context_snapshot_id,
            lease_until,
            last_heartbeat_at,
            started_at,
            finished_at,
            task_skill_ref,
            toolpacks_snapshot_json,
            base_commit,
            branch_name,
            worktree_path,
            created_at,
            updated_at
        from task_runs
        where status in ('RUNNING', 'WAITING_FOREMAN')
          and lease_until < #{leaseBefore}
        order by lease_until asc
        limit #{limit}
        """)
    List<TaskRunRow> findExpiredActiveRuns(
        @Param("leaseBefore") java.sql.Timestamp leaseBefore,
        @Param("limit") int limit
    );

    @Update("""
        update task_runs
        set
            status = 'FAILED',
            finished_at = #{updatedAt},
            updated_at = #{updatedAt}
        where run_id = #{runId}
          and status in ('RUNNING', 'WAITING_FOREMAN')
          and lease_until < #{leaseBefore}
        """)
    int markFailedIfLeaseExpired(
        @Param("runId") String runId,
        @Param("leaseBefore") java.sql.Timestamp leaseBefore,
        @Param("updatedAt") java.sql.Timestamp updatedAt
    );
}

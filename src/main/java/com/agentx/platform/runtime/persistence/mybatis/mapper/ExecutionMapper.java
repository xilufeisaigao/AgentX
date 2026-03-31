package com.agentx.platform.runtime.persistence.mybatis.mapper;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ExecutionMapper {

    @Select("""
            select
              snapshot_id as snapshotId,
              task_id as taskId,
              run_kind as runKind,
              status,
              trigger_type as triggerType,
              source_fingerprint as sourceFingerprint,
              task_context_ref as taskContextRef,
              task_skill_ref as taskSkillRef,
              retained_until as retainedUntil
            from task_context_snapshots
            where task_id = #{taskId}
              and run_kind = #{runKind}
            order by created_at, snapshot_id
            """)
    List<Map<String, Object>> listSnapshotRows(@Param("taskId") String taskId, @Param("runKind") RunKind runKind);

    @Select("""
            select
              snapshot_id as snapshotId,
              task_id as taskId,
              run_kind as runKind,
              status,
              trigger_type as triggerType,
              source_fingerprint as sourceFingerprint,
              task_context_ref as taskContextRef,
              task_skill_ref as taskSkillRef,
              retained_until as retainedUntil
            from task_context_snapshots
            where task_id = #{taskId}
              and run_kind = #{runKind}
              and status = 'READY'
            order by created_at desc
            limit 1
            """)
    Map<String, Object> findLatestReadySnapshotRow(@Param("taskId") String taskId, @Param("runKind") RunKind runKind);

    @Select("""
            select distinct
              api.agent_instance_id as agentInstanceId,
              api.agent_id as agentId,
              api.runtime_type as runtimeType,
              api.status as status,
              api.launch_mode as launchMode,
              api.current_workflow_run_id as currentWorkflowRunId
            from agent_pool_instances api
            join agent_definition_capability_packs adcp on adcp.agent_id = api.agent_id
            join agent_definitions ad on ad.agent_id = api.agent_id
            where adcp.capability_pack_id = #{capabilityPackId}
              and api.status = 'READY'
              and ad.enabled = true
            order by api.agent_instance_id
            """)
    List<Map<String, Object>> listReadyAgentRows(@Param("capabilityPackId") String capabilityPackId);

    @Select("""
            select
              agent_instance_id as agentInstanceId,
              agent_id as agentId,
              runtime_type as runtimeType,
              status,
              launch_mode as launchMode,
              current_workflow_run_id as currentWorkflowRunId,
              lease_until as leaseUntil,
              last_heartbeat_at as lastHeartbeatAt,
              endpoint_ref as endpointRef,
              runtime_metadata_json as runtimeMetadataJson
            from agent_pool_instances
            where agent_instance_id = #{agentInstanceId}
            """)
    Map<String, Object> findAgentInstanceRow(@Param("agentInstanceId") String agentInstanceId);

    @Select("""
            select
              run_id as runId,
              task_id as taskId,
              agent_instance_id as agentInstanceId,
              status,
              run_kind as runKind,
              context_snapshot_id as contextSnapshotId,
              lease_until as leaseUntil,
              last_heartbeat_at as lastHeartbeatAt,
              started_at as startedAt,
              finished_at as finishedAt,
              execution_contract_json as executionContractJson
            from task_runs
            where run_id = #{runId}
            """)
    Map<String, Object> findTaskRunRow(@Param("runId") String runId);

    @Select("""
            select
              run_id as runId,
              task_id as taskId,
              agent_instance_id as agentInstanceId,
              status,
              run_kind as runKind,
              context_snapshot_id as contextSnapshotId,
              lease_until as leaseUntil,
              last_heartbeat_at as lastHeartbeatAt,
              started_at as startedAt,
              finished_at as finishedAt,
              execution_contract_json as executionContractJson
            from task_runs
            where task_id = #{taskId}
            order by started_at, run_id
            """)
    List<Map<String, Object>> listTaskRunRows(@Param("taskId") String taskId);

    @Select("""
            select
              event_id as eventId,
              run_id as runId,
              event_type as eventType,
              body,
              data_json as dataJson
            from task_run_events
            where run_id = #{runId}
            order by created_at, event_id
            """)
    List<Map<String, Object>> listTaskRunEventRows(@Param("runId") String runId);

    @Select("""
            select
              run_id as runId,
              task_id as taskId,
              agent_instance_id as agentInstanceId,
              status,
              run_kind as runKind,
              context_snapshot_id as contextSnapshotId,
              lease_until as leaseUntil,
              last_heartbeat_at as lastHeartbeatAt,
              started_at as startedAt,
              finished_at as finishedAt,
              execution_contract_json as executionContractJson
            from task_runs
            where status in ('QUEUED', 'RUNNING')
            order by started_at, run_id
            """)
    List<Map<String, Object>> listActiveTaskRunRows();

    @Select("""
            select
              workspace_id as workspaceId,
              run_id as runId,
              task_id as taskId,
              status,
              repo_root as repoRoot,
              worktree_path as worktreePath,
              branch_name as branchName,
              base_commit as baseCommit,
              head_commit as headCommit,
              merge_commit as mergeCommit,
              cleanup_status as cleanupStatus
            from git_workspaces
            where run_id = #{runId}
            """)
    Map<String, Object> findWorkspaceByRunRow(@Param("runId") String runId);

    @Select("""
            select
              workspace_id as workspaceId,
              run_id as runId,
              task_id as taskId,
              status,
              repo_root as repoRoot,
              worktree_path as worktreePath,
              branch_name as branchName,
              base_commit as baseCommit,
              head_commit as headCommit,
              merge_commit as mergeCommit,
              cleanup_status as cleanupStatus
            from git_workspaces
            where task_id = #{taskId}
            order by created_at, workspace_id
            """)
    List<Map<String, Object>> listWorkspaceRows(@Param("taskId") String taskId);

    @Select("""
            select
              workspace_id as workspaceId,
              run_id as runId,
              task_id as taskId,
              status,
              repo_root as repoRoot,
              worktree_path as worktreePath,
              branch_name as branchName,
              base_commit as baseCommit,
              head_commit as headCommit,
              merge_commit as mergeCommit,
              cleanup_status as cleanupStatus
            from git_workspaces
            where cleanup_status <> 'DONE'
            order by updated_at, workspace_id
            """)
    List<Map<String, Object>> listWorkspaceRowsPendingCleanup();

    @Select("""
            select
              agent_instance_id as agentInstanceId,
              agent_id as agentId,
              runtime_type as runtimeType,
              status,
              launch_mode as launchMode,
              current_workflow_run_id as currentWorkflowRunId,
              lease_until as leaseUntil,
              last_heartbeat_at as lastHeartbeatAt,
              endpoint_ref as endpointRef,
              runtime_metadata_json as runtimeMetadataJson
            from agent_pool_instances
            where status in ('PROVISIONING', 'READY')
            order by updated_at, agent_instance_id
            """)
    List<Map<String, Object>> listActiveAgentRows();

    @Insert("""
            insert into agent_pool_instances (
              agent_instance_id,
              agent_id,
              runtime_type,
              status,
              launch_mode,
              current_workflow_run_id,
              lease_until,
              last_heartbeat_at,
              endpoint_ref,
              runtime_metadata_json
            ) values (
              #{agent.agentInstanceId},
              #{agent.agentId},
              #{agent.runtimeType},
              #{agent.status},
              #{agent.launchMode},
              #{agent.currentWorkflowRunId},
              #{agent.leaseUntil},
              #{agent.lastHeartbeatAt},
              #{agent.endpointRef},
              cast(#{runtimeMetadataJson} as json)
            )
            on duplicate key update
              agent_id = values(agent_id),
              runtime_type = values(runtime_type),
              status = values(status),
              launch_mode = values(launch_mode),
              current_workflow_run_id = values(current_workflow_run_id),
              lease_until = values(lease_until),
              last_heartbeat_at = values(last_heartbeat_at),
              endpoint_ref = values(endpoint_ref),
              runtime_metadata_json = values(runtime_metadata_json)
            """)
    void upsertAgentInstance(
            @Param("agent") AgentPoolInstance agent,
            @Param("runtimeMetadataJson") String runtimeMetadataJson
    );

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
              retained_until
            ) values (
              #{snapshot.snapshotId},
              #{snapshot.taskId},
              #{snapshot.runKind},
              #{snapshot.status},
              #{snapshot.triggerType},
              #{snapshot.sourceFingerprint},
              #{snapshot.taskContextRef},
              #{snapshot.taskSkillRef},
              #{snapshot.retainedUntil}
            )
            on duplicate key update
              status = values(status),
              trigger_type = values(trigger_type),
              source_fingerprint = values(source_fingerprint),
              task_context_ref = values(task_context_ref),
              task_skill_ref = values(task_skill_ref),
              retained_until = values(retained_until)
            """)
    void upsertTaskContextSnapshot(@Param("snapshot") TaskContextSnapshot snapshot);

    @Insert("""
            insert into task_runs (
              run_id,
              task_id,
              agent_instance_id,
              status,
              run_kind,
              context_snapshot_id,
              lease_until,
              last_heartbeat_at,
              started_at,
              finished_at,
              execution_contract_json
            ) values (
              #{taskRun.runId},
              #{taskRun.taskId},
              #{taskRun.agentInstanceId},
              #{taskRun.status},
              #{taskRun.runKind},
              #{taskRun.contextSnapshotId},
              #{taskRun.leaseUntil},
              #{taskRun.lastHeartbeatAt},
              #{taskRun.startedAt},
              #{taskRun.finishedAt},
              cast(#{taskRun.executionContractJson.json} as json)
            )
            on duplicate key update
              agent_instance_id = values(agent_instance_id),
              status = values(status),
              run_kind = values(run_kind),
              context_snapshot_id = values(context_snapshot_id),
              lease_until = values(lease_until),
              last_heartbeat_at = values(last_heartbeat_at),
              started_at = values(started_at),
              finished_at = values(finished_at),
              execution_contract_json = values(execution_contract_json)
            """)
    void upsertTaskRun(@Param("taskRun") TaskRun taskRun);

    @Insert("""
            insert into task_run_events (
              event_id,
              run_id,
              event_type,
              body,
              data_json
            ) values (
              #{event.eventId},
              #{event.runId},
              #{event.eventType},
              #{event.body},
              cast(#{dataJson} as json)
            )
            """)
    void insertTaskRunEvent(@Param("event") TaskRunEvent event, @Param("dataJson") String dataJson);

    @Insert("""
            insert into git_workspaces (
              workspace_id,
              run_id,
              task_id,
              status,
              repo_root,
              worktree_path,
              branch_name,
              base_commit,
              head_commit,
              merge_commit,
              cleanup_status
            ) values (
              #{workspace.workspaceId},
              #{workspace.runId},
              #{workspace.taskId},
              #{workspace.status},
              #{workspace.repoRoot},
              #{workspace.worktreePath},
              #{workspace.branchName},
              #{workspace.baseCommit},
              #{workspace.headCommit},
              #{workspace.mergeCommit},
              #{workspace.cleanupStatus}
            )
            on duplicate key update
              status = values(status),
              repo_root = values(repo_root),
              worktree_path = values(worktree_path),
              branch_name = values(branch_name),
              base_commit = values(base_commit),
              head_commit = values(head_commit),
              merge_commit = values(merge_commit),
              cleanup_status = values(cleanup_status)
            """)
    void upsertWorkspace(@Param("workspace") GitWorkspace workspace);
}

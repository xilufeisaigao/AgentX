package com.agentx.platform.runtime.persistence.mybatis.repository;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.AgentPoolStatus;
import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskContextSnapshotStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;
import com.agentx.platform.domain.execution.model.TaskRunStatus;
import com.agentx.platform.domain.execution.port.ExecutionStore;
import com.agentx.platform.runtime.persistence.mybatis.mapper.ExecutionMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MybatisExecutionRepository implements ExecutionStore {

    private final ExecutionMapper executionMapper;

    public MybatisExecutionRepository(ExecutionMapper executionMapper) {
        this.executionMapper = executionMapper;
    }

    @Override
    public Optional<TaskContextSnapshot> findLatestReadySnapshot(String taskId, RunKind runKind) {
        Map<String, Object> row = executionMapper.findLatestReadySnapshotRow(taskId, runKind);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(snapshot(row));
    }

    @Override
    public List<TaskContextSnapshot> listSnapshots(String taskId, RunKind runKind) {
        return executionMapper.listSnapshotRows(taskId, runKind).stream()
                .map(this::snapshot)
                .toList();
    }

    @Override
    public List<AgentPoolInstance> listReadyAgents(String capabilityPackId) {
        return executionMapper.listReadyAgentRows(capabilityPackId).stream()
                .map(this::agent)
                .toList();
    }

    @Override
    public Optional<AgentPoolInstance> findAgentInstance(String agentInstanceId) {
        Map<String, Object> row = executionMapper.findAgentInstanceRow(agentInstanceId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(agent(row));
    }

    @Override
    public Optional<TaskRun> findTaskRun(String runId) {
        Map<String, Object> row = executionMapper.findTaskRunRow(runId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(taskRun(row));
    }

    @Override
    public List<TaskRun> listTaskRuns(String taskId) {
        return executionMapper.listTaskRunRows(taskId).stream()
                .map(this::taskRun)
                .toList();
    }

    @Override
    public Optional<GitWorkspace> findWorkspaceByRun(String runId) {
        Map<String, Object> row = executionMapper.findWorkspaceByRunRow(runId);
        if (MybatisRowReader.isEmpty(row)) {
            return Optional.empty();
        }
        return Optional.of(workspace(row));
    }

    @Override
    public List<GitWorkspace> listWorkspaces(String taskId) {
        return executionMapper.listWorkspaceRows(taskId).stream()
                .map(this::workspace)
                .toList();
    }

    @Override
    public void saveAgentInstance(AgentPoolInstance agentPoolInstance) {
        executionMapper.upsertAgentInstance(
                agentPoolInstance,
                agentPoolInstance.runtimeMetadataJson() == null ? null : agentPoolInstance.runtimeMetadataJson().json()
        );
    }

    @Override
    public void saveTaskContextSnapshot(TaskContextSnapshot snapshot) {
        executionMapper.upsertTaskContextSnapshot(snapshot);
    }

    @Override
    public void saveTaskRun(TaskRun taskRun) {
        executionMapper.upsertTaskRun(taskRun);
    }

    @Override
    public void appendTaskRunEvent(TaskRunEvent taskRunEvent) {
        executionMapper.insertTaskRunEvent(
                taskRunEvent,
                taskRunEvent.dataJson() == null ? null : taskRunEvent.dataJson().json()
        );
    }

    @Override
    public void saveWorkspace(GitWorkspace gitWorkspace) {
        executionMapper.upsertWorkspace(gitWorkspace);
    }

    private TaskContextSnapshot snapshot(Map<String, Object> row) {
        return new TaskContextSnapshot(
                MybatisRowReader.string(row, "snapshotId"),
                MybatisRowReader.string(row, "taskId"),
                MybatisRowReader.enumValue(row, "runKind", RunKind.class),
                MybatisRowReader.enumValue(row, "status", TaskContextSnapshotStatus.class),
                MybatisRowReader.string(row, "triggerType"),
                MybatisRowReader.string(row, "sourceFingerprint"),
                MybatisRowReader.nullableString(row, "taskContextRef"),
                MybatisRowReader.nullableString(row, "taskSkillRef"),
                MybatisRowReader.localDateTime(row, "retainedUntil")
        );
    }

    private AgentPoolInstance agent(Map<String, Object> row) {
        return new AgentPoolInstance(
                MybatisRowReader.string(row, "agentInstanceId"),
                MybatisRowReader.string(row, "agentId"),
                MybatisRowReader.string(row, "runtimeType"),
                MybatisRowReader.enumValue(row, "status", AgentPoolStatus.class),
                MybatisRowReader.string(row, "launchMode"),
                MybatisRowReader.nullableString(row, "currentWorkflowRunId"),
                MybatisRowReader.nullableLocalDateTime(row, "leaseUntil"),
                MybatisRowReader.nullableLocalDateTime(row, "lastHeartbeatAt"),
                MybatisRowReader.nullableString(row, "endpointRef"),
                MybatisRowReader.nullableJsonPayload(row, "runtimeMetadataJson")
        );
    }

    private TaskRun taskRun(Map<String, Object> row) {
        return new TaskRun(
                MybatisRowReader.string(row, "runId"),
                MybatisRowReader.string(row, "taskId"),
                MybatisRowReader.string(row, "agentInstanceId"),
                MybatisRowReader.enumValue(row, "status", TaskRunStatus.class),
                MybatisRowReader.enumValue(row, "runKind", RunKind.class),
                MybatisRowReader.string(row, "contextSnapshotId"),
                MybatisRowReader.localDateTime(row, "leaseUntil"),
                MybatisRowReader.localDateTime(row, "lastHeartbeatAt"),
                MybatisRowReader.localDateTime(row, "startedAt"),
                MybatisRowReader.nullableLocalDateTime(row, "finishedAt"),
                MybatisRowReader.jsonPayload(row, "executionContractJson")
        );
    }

    private GitWorkspace workspace(Map<String, Object> row) {
        return new GitWorkspace(
                MybatisRowReader.string(row, "workspaceId"),
                MybatisRowReader.string(row, "runId"),
                MybatisRowReader.string(row, "taskId"),
                MybatisRowReader.enumValue(row, "status", GitWorkspaceStatus.class),
                MybatisRowReader.string(row, "repoRoot"),
                MybatisRowReader.string(row, "worktreePath"),
                MybatisRowReader.string(row, "branchName"),
                MybatisRowReader.string(row, "baseCommit"),
                MybatisRowReader.nullableString(row, "headCommit"),
                MybatisRowReader.nullableString(row, "mergeCommit"),
                MybatisRowReader.enumValue(row, "cleanupStatus", CleanupStatus.class)
        );
    }
}

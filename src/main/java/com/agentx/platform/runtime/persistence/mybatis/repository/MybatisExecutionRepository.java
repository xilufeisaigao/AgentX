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
        return Optional.of(new TaskContextSnapshot(
                MybatisRowReader.string(row, "snapshotId"),
                MybatisRowReader.string(row, "taskId"),
                MybatisRowReader.enumValue(row, "runKind", RunKind.class),
                MybatisRowReader.enumValue(row, "status", TaskContextSnapshotStatus.class),
                MybatisRowReader.string(row, "triggerType"),
                MybatisRowReader.string(row, "sourceFingerprint"),
                MybatisRowReader.nullableString(row, "taskContextRef"),
                MybatisRowReader.nullableString(row, "taskSkillRef"),
                MybatisRowReader.nullableLocalDateTime(row, "retainedUntil")
        ));
    }

    @Override
    public List<AgentPoolInstance> listReadyAgents(String capabilityPackId) {
        return executionMapper.listReadyAgentRows(capabilityPackId).stream()
                .map(row -> new AgentPoolInstance(
                        MybatisRowReader.string(row, "agentInstanceId"),
                        MybatisRowReader.string(row, "agentId"),
                        MybatisRowReader.string(row, "runtimeType"),
                        MybatisRowReader.enumValue(row, "status", AgentPoolStatus.class),
                        MybatisRowReader.string(row, "launchMode"),
                        MybatisRowReader.nullableString(row, "currentWorkflowRunId")
                ))
                .toList();
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
        executionMapper.insertTaskRunEvent(taskRunEvent);
    }

    @Override
    public void saveWorkspace(GitWorkspace gitWorkspace) {
        executionMapper.upsertWorkspace(gitWorkspace);
    }
}

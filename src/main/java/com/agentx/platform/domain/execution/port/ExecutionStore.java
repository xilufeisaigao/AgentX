package com.agentx.platform.domain.execution.port;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.RunKind;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.execution.model.TaskRunEvent;

import java.util.List;
import java.util.Optional;

public interface ExecutionStore {

    Optional<TaskContextSnapshot> findLatestReadySnapshot(String taskId, RunKind runKind);

    List<TaskContextSnapshot> listSnapshots(String taskId, RunKind runKind);

    List<AgentPoolInstance> listReadyAgents(String capabilityPackId);

    Optional<AgentPoolInstance> findAgentInstance(String agentInstanceId);

    Optional<TaskRun> findTaskRun(String runId);

    List<TaskRun> listTaskRuns(String taskId);

    Optional<GitWorkspace> findWorkspaceByRun(String runId);

    List<GitWorkspace> listWorkspaces(String taskId);

    void saveAgentInstance(AgentPoolInstance agentPoolInstance);

    void saveTaskContextSnapshot(TaskContextSnapshot snapshot);

    void saveTaskRun(TaskRun taskRun);

    void appendTaskRunEvent(TaskRunEvent taskRunEvent);

    void saveWorkspace(GitWorkspace gitWorkspace);
}

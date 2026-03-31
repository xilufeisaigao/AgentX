package com.agentx.platform.runtime.application.workflow;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskContextSnapshot;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.flow.model.WorkflowNodeRun;
import com.agentx.platform.domain.flow.model.WorkflowRun;
import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.intake.model.Ticket;
import com.agentx.platform.domain.planning.model.WorkTask;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record WorkflowRuntimeSnapshot(
        WorkflowRun workflowRun,
        Optional<WorkflowProfileRef> workflowProfile,
        Optional<RequirementDoc> requirementDoc,
        List<RequirementVersion> requirementVersions,
        List<Ticket> tickets,
        List<WorkTask> tasks,
        List<TaskContextSnapshot> snapshots,
        List<TaskRun> taskRuns,
        List<GitWorkspace> workspaces,
        List<WorkflowNodeRun> nodeRuns
) {

    public WorkflowRuntimeSnapshot {
        Objects.requireNonNull(workflowRun, "workflowRun must not be null");
        workflowProfile = workflowProfile == null ? Optional.empty() : workflowProfile;
        requirementDoc = requirementDoc == null ? Optional.empty() : requirementDoc;
        requirementVersions = List.copyOf(Objects.requireNonNull(requirementVersions, "requirementVersions must not be null"));
        tickets = List.copyOf(Objects.requireNonNull(tickets, "tickets must not be null"));
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots must not be null"));
        taskRuns = List.copyOf(Objects.requireNonNull(taskRuns, "taskRuns must not be null"));
        workspaces = List.copyOf(Objects.requireNonNull(workspaces, "workspaces must not be null"));
        nodeRuns = List.copyOf(Objects.requireNonNull(nodeRuns, "nodeRuns must not be null"));
    }
}

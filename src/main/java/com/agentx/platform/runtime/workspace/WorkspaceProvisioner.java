package com.agentx.platform.runtime.workspace;

import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.planning.model.WorkTask;

import java.nio.file.Path;

public interface WorkspaceProvisioner {

    default GitWorkspace allocate(String workflowRunId, WorkTask task, TaskRun taskRun) {
        return allocate(workflowRunId, task, taskRun, null);
    }

    GitWorkspace allocate(String workflowRunId, WorkTask task, TaskRun taskRun, String baseRevision);

    GitWorkspace refreshHeadCommit(GitWorkspace workspace);

    GitWorkspace mergeCandidate(GitWorkspace workspace);

    Path checkoutMergeCandidate(GitWorkspace workspace);

    void releaseCheckout(Path checkoutPath);

    GitWorkspace cleanup(GitWorkspace workspace);
}

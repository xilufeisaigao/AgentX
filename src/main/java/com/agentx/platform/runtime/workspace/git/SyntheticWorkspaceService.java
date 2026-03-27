package com.agentx.platform.runtime.workspace.git;

import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.planning.model.WorkTask;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SyntheticWorkspaceService {

    public GitWorkspace allocate(String workflowRunId, WorkTask task, TaskRun taskRun) {
        Path repoRoot = Path.of("target", "runtime-workspaces", workflowRunId);
        Path worktreePath = repoRoot.resolve("tasks").resolve(task.taskId()).resolve(taskRun.runId());
        try {
            Files.createDirectories(worktreePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create synthetic workspace for run %s".formatted(taskRun.runId()), exception);
        }
        return new GitWorkspace(
                "workspace-" + taskRun.runId(),
                taskRun.runId(),
                task.taskId(),
                GitWorkspaceStatus.READY,
                repoRoot.toAbsolutePath().toString(),
                worktreePath.toAbsolutePath().toString(),
                "branch/" + taskRun.runId(),
                syntheticCommit("base", workflowRunId, task.taskId(), taskRun.runId()),
                null,
                null,
                CleanupStatus.PENDING
        );
    }

    public GitWorkspace withHeadCommit(GitWorkspace workspace) {
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                workspace.status(),
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                syntheticCommit("head", workspace.taskId(), workspace.runId()),
                workspace.mergeCommit(),
                workspace.cleanupStatus()
        );
    }

    public GitWorkspace markMerged(GitWorkspace workspace) {
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                GitWorkspaceStatus.MERGED,
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                syntheticCommit("merge", workspace.taskId(), workspace.runId()),
                CleanupStatus.PENDING
        );
    }

    public GitWorkspace markCleaned(GitWorkspace workspace) {
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                GitWorkspaceStatus.CLEANED,
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                workspace.mergeCommit(),
                CleanupStatus.DONE
        );
    }

    private String syntheticCommit(String prefix, String... values) {
        String seed = prefix + "-" + String.join("-", values);
        return Integer.toHexString(seed.hashCode()).replace("-", "a") + prefix.length();
    }
}

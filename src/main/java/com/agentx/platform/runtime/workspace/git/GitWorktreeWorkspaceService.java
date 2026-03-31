package com.agentx.platform.runtime.workspace.git;

import com.agentx.platform.domain.execution.model.CleanupStatus;
import com.agentx.platform.domain.execution.model.GitWorkspace;
import com.agentx.platform.domain.execution.model.GitWorkspaceStatus;
import com.agentx.platform.domain.execution.model.TaskRun;
import com.agentx.platform.domain.planning.model.WorkTask;
import com.agentx.platform.domain.shared.model.WriteScope;
import com.agentx.platform.runtime.support.CommandResult;
import com.agentx.platform.runtime.support.CommandRunner;
import com.agentx.platform.runtime.support.CommandSpec;
import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import com.agentx.platform.runtime.workspace.WorkspaceProvisioner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GitWorktreeWorkspaceService implements WorkspaceProvisioner {

    private final RuntimeInfrastructureProperties runtimeProperties;
    private final CommandRunner commandRunner;

    public GitWorktreeWorkspaceService(
            RuntimeInfrastructureProperties runtimeProperties,
            CommandRunner commandRunner
    ) {
        this.runtimeProperties = runtimeProperties;
        this.commandRunner = commandRunner;
    }

    @Override
    public GitWorkspace allocate(String workflowRunId, WorkTask task, TaskRun taskRun, String baseRevision) {
        Path repoRoot = runtimeProperties.requiredRepoRoot();
        Path workspaceRoot = runtimeProperties.getWorkspaceRoot().toAbsolutePath().normalize();
        Path worktreePath = workspaceRoot.resolve(workflowRunId).resolve(task.taskId()).resolve(taskRun.runId());
        try {
            Files.createDirectories(workspaceRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create workspace root " + workspaceRoot, exception);
        }

        String baseRef = baseRevision == null || baseRevision.isBlank()
                ? runtimeProperties.getBaseBranch()
                : baseRevision;
        String baseCommit = gitOutput(repoRoot, List.of("git", "rev-parse", baseRef));
        String branchName = branchName(task, taskRun);
        deleteRecursivelyIfExists(worktreePath);
        git(repoRoot, List.of("git", "worktree", "add", "--force", "-b", branchName, worktreePath.toString(), baseCommit));
        git(worktreePath, List.of("git", "config", "user.email", "agentx-runtime@example.local"));
        git(worktreePath, List.of("git", "config", "user.name", "AgentX Runtime"));
        ensureWriteScopes(worktreePath, task.writeScopes());

        return new GitWorkspace(
                "workspace-" + taskRun.runId(),
                taskRun.runId(),
                task.taskId(),
                GitWorkspaceStatus.READY,
                repoRoot.toString(),
                worktreePath.toString(),
                branchName,
                baseCommit,
                null,
                null,
                CleanupStatus.PENDING
        );
    }

    @Override
    public GitWorkspace refreshHeadCommit(GitWorkspace workspace) {
        String headCommit = gitOutput(Path.of(workspace.worktreePath()), List.of("git", "rev-parse", "HEAD"));
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                workspace.status(),
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                headCommit,
                workspace.mergeCommit(),
                workspace.cleanupStatus()
        );
    }

    @Override
    public GitWorkspace mergeCandidate(GitWorkspace workspace) {
        Path repoRoot = Path.of(workspace.repoRoot());
        Path mergeWorktreePath = mergeWorktreePath(workspace);
        deleteRecursivelyIfExists(mergeWorktreePath);
        git(repoRoot, List.of("git", "worktree", "add", "--detach", mergeWorktreePath.toString(), workspace.baseCommit()));
        String mergeBranch = mergeBranchName(workspace);
        try {
            git(mergeWorktreePath, List.of("git", "checkout", "-B", mergeBranch));
            git(mergeWorktreePath, List.of("git", "merge", "--no-ff", workspace.branchName(), "-m", "merge " + workspace.runId()));
            String mergeCommit = gitOutput(mergeWorktreePath, List.of("git", "rev-parse", "HEAD"));
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
                    mergeCommit,
                    CleanupStatus.PENDING
            );
        } finally {
            releaseCheckout(mergeWorktreePath);
        }
    }

    @Override
    public Path checkoutMergeCandidate(GitWorkspace workspace) {
        Path verifyPath = verifyWorktreePath(workspace);
        deleteRecursivelyIfExists(verifyPath);
        git(Path.of(workspace.repoRoot()), List.of("git", "worktree", "add", "--detach", verifyPath.toString(), mergeBranchName(workspace)));
        return verifyPath;
    }

    @Override
    public void releaseCheckout(Path checkoutPath) {
        if (checkoutPath == null) {
            return;
        }
        commandRunner.run(new CommandSpec(
                List.of("git", "worktree", "remove", "--force", checkoutPath.toString()),
                runtimeProperties.requiredRepoRoot(),
                Duration.ofSeconds(15),
                Map.of()
        ));
        deleteRecursivelyIfExists(checkoutPath);
    }

    @Override
    public GitWorkspace cleanup(GitWorkspace workspace) {
        CleanupStatus cleanupStatus = CleanupStatus.DONE;
        try {
            releaseCheckout(Path.of(workspace.worktreePath()));
            commandRunner.run(new CommandSpec(
                    List.of("git", "branch", "-D", workspace.branchName()),
                    Path.of(workspace.repoRoot()),
                    Duration.ofSeconds(10),
                    Map.of()
            ));
            if (workspace.mergeCommit() != null) {
                commandRunner.run(new CommandSpec(
                        List.of("git", "branch", "-D", mergeBranchName(workspace)),
                        Path.of(workspace.repoRoot()),
                        Duration.ofSeconds(10),
                        Map.of()
                ));
            }
        } catch (RuntimeException exception) {
            cleanupStatus = CleanupStatus.FAILED;
        }
        return new GitWorkspace(
                workspace.workspaceId(),
                workspace.runId(),
                workspace.taskId(),
                cleanupStatus == CleanupStatus.DONE ? GitWorkspaceStatus.CLEANED : workspace.status(),
                workspace.repoRoot(),
                workspace.worktreePath(),
                workspace.branchName(),
                workspace.baseCommit(),
                workspace.headCommit(),
                workspace.mergeCommit(),
                cleanupStatus
        );
    }

    private String branchName(WorkTask task, TaskRun taskRun) {
        return "task/" + sanitize(task.taskId()) + "/" + sanitize(taskRun.runId());
    }

    private String mergeBranchName(GitWorkspace workspace) {
        return "merge/" + sanitize(workspace.runId());
    }

    private Path mergeWorktreePath(GitWorkspace workspace) {
        return runtimeProperties.getWorkspaceRoot()
                .toAbsolutePath()
                .normalize()
                .resolve("merge")
                .resolve(workspace.runId());
    }

    private Path verifyWorktreePath(GitWorkspace workspace) {
        return runtimeProperties.getWorkspaceRoot()
                .toAbsolutePath()
                .normalize()
                .resolve("verify")
                .resolve(workspace.runId());
    }

    private void git(Path workingDirectory, List<String> command) {
        CommandResult result = commandRunner.run(new CommandSpec(
                command,
                workingDirectory,
                Duration.ofSeconds(20),
                Map.of()
        ));
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("git command failed: " + String.join(" ", command) + System.lineSeparator() + summarize(result));
        }
    }

    private String gitOutput(Path workingDirectory, List<String> command) {
        CommandResult result = commandRunner.run(new CommandSpec(
                command,
                workingDirectory,
                Duration.ofSeconds(20),
                Map.of()
        ));
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("git command failed: " + String.join(" ", command) + System.lineSeparator() + summarize(result));
        }
        return result.stdout().trim();
    }

    private String summarize(CommandResult result) {
        if (!result.stderr().isBlank()) {
            return result.stderr();
        }
        if (!result.stdout().isBlank()) {
            return result.stdout();
        }
        return "command failed without output";
    }

    private String sanitize(String rawValue) {
        return rawValue.replaceAll("[^a-zA-Z0-9._/-]", "-");
    }

    private void deleteRecursivelyIfExists(Path path) {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((left, right) -> right.compareTo(left))
                    .forEach(candidate -> {
                        try {
                            Files.deleteIfExists(candidate);
                        } catch (IOException exception) {
                            throw new IllegalStateException("failed to delete " + candidate, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("failed to clean path " + path, exception);
        }
    }

    private void ensureWriteScopes(Path worktreePath, List<WriteScope> writeScopes) {
        for (WriteScope writeScope : writeScopes) {
            Path scopePath = worktreePath.resolve(writeScope.path()).normalize();
            if (!scopePath.startsWith(worktreePath)) {
                throw new IllegalStateException("write scope escapes workspace root: " + writeScope.path());
            }
            try {
                Files.createDirectories(scopePath);
            } catch (IOException exception) {
                throw new IllegalStateException("failed to create write scope " + scopePath, exception);
            }
        }
    }
}

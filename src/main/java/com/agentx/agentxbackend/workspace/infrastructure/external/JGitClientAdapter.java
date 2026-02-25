package com.agentx.agentxbackend.workspace.infrastructure.external;

import com.agentx.agentxbackend.workspace.application.port.out.GitClientPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class JGitClientAdapter implements GitClientPort {

    private final String gitExecutable;
    private final Path repoRoot;
    private final String taskBranchPrefix;
    private final int commandTimeoutMs;

    public JGitClientAdapter(
        @Value("${agentx.workspace.git.executable:git}") String gitExecutable,
        @Value("${agentx.workspace.git.repo-root:.}") String repoRoot,
        @Value("${agentx.workspace.git.task-branch-prefix:task/}") String taskBranchPrefix,
        @Value("${agentx.workspace.git.command-timeout-ms:120000}") int commandTimeoutMs
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.taskBranchPrefix = taskBranchPrefix == null || taskBranchPrefix.isBlank() ? "task/" : taskBranchPrefix.trim();
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
    }

    @Override
    public void createRunBranchAndWorktree(String runId, String baseCommit, String branchName, String worktreePath) {
        requireNotBlank(runId, "runId");
        String normalizedBaseCommit = requireNotBlank(baseCommit, "baseCommit");
        String normalizedBranchName = requireNotBlank(branchName, "branchName");
        Path resolvedWorktree = resolveWorktreePath(worktreePath);

        runGit(List.of("rev-parse", "--verify", normalizedBaseCommit + "^{commit}"), repoRoot);
        runGit(List.of("branch", "-f", normalizedBranchName, normalizedBaseCommit), repoRoot);

        deleteIfExists(resolvedWorktree);
        runGit(List.of("worktree", "add", "--force", resolvedWorktree.toString(), normalizedBranchName), repoRoot);

        String cleanliness = runGit(List.of("status", "--porcelain"), resolvedWorktree).trim();
        if (!cleanliness.isEmpty()) {
            try {
                runGit(List.of("worktree", "remove", "--force", resolvedWorktree.toString()), repoRoot);
            } catch (RuntimeException ignored) {
                // best effort cleanup before surfacing workspace corruption
            }
            throw new IllegalStateException("Allocated worktree is not clean: " + resolvedWorktree);
        }
    }

    @Override
    public void removeWorktree(String worktreePath) {
        Path resolvedWorktree = resolveWorktreePath(worktreePath);
        if (!Files.exists(resolvedWorktree)) {
            return;
        }
        runGit(List.of("worktree", "remove", "--force", resolvedWorktree.toString()), repoRoot);
        deleteIfExists(resolvedWorktree);
    }

    @Override
    public void updateTaskBranch(String taskId, String deliveryCommit) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedDeliveryCommit = requireNotBlank(deliveryCommit, "deliveryCommit");
        runGit(List.of("rev-parse", "--verify", normalizedDeliveryCommit + "^{commit}"), repoRoot);
        runGit(List.of("branch", "-f", buildTaskBranch(normalizedTaskId), normalizedDeliveryCommit), repoRoot);
    }

    private String runGit(List<String> args, Path commandDir) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.addAll(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(commandDir.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("Git command timeout: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                    "Git command failed (exit " + process.exitValue() + "): "
                        + String.join(" ", command) + ", output=" + output
                );
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to execute git command: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted: " + String.join(" ", command), ex);
        }
    }

    private Path resolveWorktreePath(String worktreePath) {
        String normalized = requireNotBlank(worktreePath, "worktreePath");
        Path resolved = repoRoot.resolve(normalized).toAbsolutePath().normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes repo root: " + worktreePath);
        }
        return resolved;
    }

    private static void deleteIfExists(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to delete path: " + current, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to cleanup path: " + path, ex);
        }
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String buildTaskBranch(String taskId) {
        String prefix = taskBranchPrefix.endsWith("/") ? taskBranchPrefix : taskBranchPrefix + "/";
        return prefix + taskId;
    }
}

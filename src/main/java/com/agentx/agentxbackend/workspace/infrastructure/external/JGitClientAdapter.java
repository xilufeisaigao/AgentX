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
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class JGitClientAdapter implements GitClientPort {

    private static final String WORKTREES_PREFIX = "worktrees/";

    private final String gitExecutable;
    private final Path repoRoot;
    private final String sessionRepoPrefix;
    private final String mainBranch;
    private final String taskBranchPrefix;
    private final String gitUserEmail;
    private final String gitUserName;
    private final int commandTimeoutMs;

    public JGitClientAdapter(
        @Value("${agentx.workspace.git.executable:git}") String gitExecutable,
        @Value("${agentx.workspace.git.repo-root:.}") String repoRoot,
        @Value("${agentx.workspace.git.session-repo-prefix:sessions}") String sessionRepoPrefix,
        @Value("${agentx.workspace.git.main-branch:main}") String mainBranch,
        @Value("${agentx.workspace.git.task-branch-prefix:task/}") String taskBranchPrefix,
        @Value("${agentx.git.user.email:agentx-runtime@local}") String gitUserEmail,
        @Value("${agentx.git.user.name:AgentX Runtime}") String gitUserName,
        @Value("${agentx.workspace.git.command-timeout-ms:120000}") int commandTimeoutMs
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.sessionRepoPrefix = normalizeRelativePrefix(sessionRepoPrefix, "sessions");
        this.mainBranch = mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch.trim();
        this.taskBranchPrefix = taskBranchPrefix == null || taskBranchPrefix.isBlank() ? "task/" : taskBranchPrefix.trim();
        this.gitUserEmail = gitUserEmail == null || gitUserEmail.isBlank() ? "agentx-runtime@local" : gitUserEmail.trim();
        this.gitUserName = gitUserName == null || gitUserName.isBlank() ? "AgentX Runtime" : gitUserName.trim();
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
    }

    @Override
    public void createRunBranchAndWorktree(
        String runId,
        String sessionId,
        String baseCommit,
        String branchName,
        String worktreePath
    ) {
        requireNotBlank(runId, "runId");
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedBaseCommit = requireNotBlank(baseCommit, "baseCommit");
        String normalizedBranchName = requireNotBlank(branchName, "branchName");
        Path sessionRepoRoot = ensureSessionRepo(normalizedSessionId);
        String effectiveBaseCommit = resolveEffectiveBaseCommit(sessionRepoRoot, normalizedBaseCommit);
        Path resolvedWorktree = resolveWorktreePath(sessionRepoRoot, worktreePath, normalizedSessionId);

        runGit(List.of("branch", "-f", normalizedBranchName, effectiveBaseCommit), sessionRepoRoot);

        deleteIfExists(resolvedWorktree);
        runGit(
            List.of("worktree", "add", "--force", resolvedWorktree.toString(), normalizedBranchName),
            sessionRepoRoot
        );

        String cleanliness = runGit(List.of("status", "--porcelain"), resolvedWorktree).trim();
        if (!cleanliness.isEmpty()) {
            try {
                runGit(List.of("worktree", "remove", "--force", resolvedWorktree.toString()), sessionRepoRoot);
            } catch (RuntimeException ignored) {
                // best effort cleanup before surfacing workspace corruption
            }
            throw new IllegalStateException("Allocated worktree is not clean: " + resolvedWorktree);
        }
    }

    @Override
    public void removeWorktree(String worktreePath) {
        String sessionId = extractSessionIdFromWorktreePath(worktreePath);
        Path sessionRepoRoot = resolveSessionRepoPath(sessionId);
        if (!Files.exists(sessionRepoRoot.resolve(".git"))) {
            return;
        }
        Path resolvedWorktree = resolveWorktreePath(sessionRepoRoot, worktreePath, sessionId);
        if (!Files.exists(resolvedWorktree)) {
            return;
        }
        runGit(List.of("worktree", "remove", "--force", resolvedWorktree.toString()), sessionRepoRoot);
        deleteIfExists(resolvedWorktree);
    }

    @Override
    public void updateTaskBranch(String sessionId, String taskId, String deliveryCommit) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedDeliveryCommit = requireNotBlank(deliveryCommit, "deliveryCommit");
        Path sessionRepoRoot = ensureSessionRepo(normalizedSessionId);
        String taskBranch = buildTaskBranch(normalizedTaskId);
        runGit(List.of("rev-parse", "--verify", normalizedDeliveryCommit + "^{commit}"), sessionRepoRoot);
        String currentBranch = runGit(List.of("branch", "--show-current"), sessionRepoRoot).trim();
        if (taskBranch.equals(currentBranch)) {
            runGit(List.of("reset", "--hard", normalizedDeliveryCommit), sessionRepoRoot);
            return;
        }
        runGit(List.of("branch", "-f", taskBranch, normalizedDeliveryCommit), sessionRepoRoot);
    }

    private Path ensureSessionRepo(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        Path sessionRepoRoot = resolveSessionRepoPath(normalizedSessionId);
        if (Files.exists(sessionRepoRoot.resolve(".git"))) {
            return sessionRepoRoot;
        }
        synchronized (this) {
            if (Files.exists(sessionRepoRoot.resolve(".git"))) {
                return sessionRepoRoot;
            }
            createSessionRepo(sessionRepoRoot);
            return sessionRepoRoot;
        }
    }

    private void createSessionRepo(Path sessionRepoRoot) {
        try {
            Files.createDirectories(sessionRepoRoot.getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create session repo parent: " + sessionRepoRoot.getParent(), ex);
        }
        if (Files.exists(repoRoot.resolve(".git"))) {
            try {
                cloneTemplateRepo(sessionRepoRoot);
                configureGitIdentity(sessionRepoRoot);
                return;
            } catch (RuntimeException ex) {
                deleteIfExists(sessionRepoRoot);
            }
        }
        initializeIndependentRepo(sessionRepoRoot);
    }

    private void cloneTemplateRepo(Path sessionRepoRoot) {
        runGit(
            List.of(
                "clone",
                "--no-local",
                "--branch",
                mainBranch,
                "--single-branch",
                repoRoot.toString(),
                sessionRepoRoot.toString()
            ),
            repoRoot
        );
    }

    private void initializeIndependentRepo(Path sessionRepoRoot) {
        try {
            Files.createDirectories(sessionRepoRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create session repo root: " + sessionRepoRoot, ex);
        }
        if (!tryInitRepoWithMainBranch(sessionRepoRoot)) {
            runGit(List.of("init"), sessionRepoRoot);
            runGit(List.of("checkout", "-B", mainBranch), sessionRepoRoot);
        }
        configureGitIdentity(sessionRepoRoot);
        ensureInitialCommit(sessionRepoRoot);
    }

    private boolean tryInitRepoWithMainBranch(Path sessionRepoRoot) {
        ProcessResult result = runGitAllowExitCodes(
            List.of("init", "-b", mainBranch),
            sessionRepoRoot,
            Set.of(0, 1)
        );
        return result.exitCode() == 0;
    }

    private void configureGitIdentity(Path sessionRepoRoot) {
        runGit(List.of("config", "user.email", gitUserEmail), sessionRepoRoot);
        runGit(List.of("config", "user.name", gitUserName), sessionRepoRoot);
    }

    private void ensureInitialCommit(Path sessionRepoRoot) {
        Path readmePath = sessionRepoRoot.resolve("README.md");
        if (!Files.exists(readmePath)) {
            try {
                Files.writeString(
                    readmePath,
                    "# AgentX Session Workspace\n\nThis repository is managed by AgentX.\n",
                    StandardCharsets.UTF_8
                );
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to write session README: " + readmePath, ex);
            }
        }
        String status = runGit(List.of("status", "--porcelain"), sessionRepoRoot).trim();
        if (status.isEmpty()) {
            return;
        }
        runGit(List.of("add", "-A"), sessionRepoRoot);
        runGit(List.of("commit", "-m", "init session workspace baseline"), sessionRepoRoot);
    }

    private String resolveEffectiveBaseCommit(Path sessionRepoRoot, String baseCommit) {
        String normalized = requireNotBlank(baseCommit, "baseCommit");
        if (isValidCommit(sessionRepoRoot, normalized)) {
            return normalized;
        }
        if (isValidCommit(sessionRepoRoot, mainBranch)) {
            return runGit(List.of("rev-parse", mainBranch), sessionRepoRoot).trim();
        }
        return runGit(List.of("rev-parse", "HEAD"), sessionRepoRoot).trim();
    }

    private boolean isValidCommit(Path sessionRepoRoot, String ref) {
        ProcessResult result = runGitAllowExitCodes(
            List.of("rev-parse", "--verify", ref + "^{commit}"),
            sessionRepoRoot,
            Set.of(0, 1, 128)
        );
        return result.exitCode() == 0;
    }

    private String extractSessionIdFromWorktreePath(String worktreePath) {
        String normalizedPath = requireNotBlank(worktreePath, "worktreePath")
            .replace('\\', '/');
        String safeNormalizedPath = normalizedPath.startsWith("./")
            ? normalizedPath.substring(2)
            : normalizedPath;
        if (!safeNormalizedPath.startsWith(WORKTREES_PREFIX)) {
            throw new IllegalArgumentException("worktreePath must start with worktrees/: " + worktreePath);
        }
        String suffix = safeNormalizedPath.substring(WORKTREES_PREFIX.length());
        int separatorIndex = suffix.indexOf('/');
        if (separatorIndex <= 0) {
            throw new IllegalArgumentException("worktreePath must include sessionId and runId: " + worktreePath);
        }
        return suffix.substring(0, separatorIndex);
    }

    private Path resolveWorktreePath(Path sessionRepoRoot, String worktreePath, String sessionId) {
        String normalized = requireNotBlank(worktreePath, "worktreePath")
            .replace('\\', '/');
        String safeNormalized = normalized.startsWith("./") ? normalized.substring(2) : normalized;
        String requiredPrefix = WORKTREES_PREFIX + requireNotBlank(sessionId, "sessionId") + "/";
        if (!safeNormalized.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException(
                "worktreePath must use session namespace prefix " + requiredPrefix + ": " + worktreePath
            );
        }
        Path resolved = sessionRepoRoot.resolve(safeNormalized).toAbsolutePath().normalize();
        if (!resolved.startsWith(sessionRepoRoot)) {
            throw new IllegalArgumentException("worktreePath escapes session repo root: " + worktreePath);
        }
        return resolved;
    }

    private Path resolveSessionRepoPath(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String safeSessionId = normalizedSessionId
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._\\-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (safeSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId has no safe characters: " + sessionId);
        }
        Path sessionRepoPath = repoRoot
            .resolve(sessionRepoPrefix)
            .resolve(safeSessionId)
            .resolve("repo")
            .toAbsolutePath()
            .normalize();
        if (!sessionRepoPath.startsWith(repoRoot)) {
            throw new IllegalArgumentException("session repo path escapes repo root: " + sessionId);
        }
        return sessionRepoPath;
    }

    private String runGit(List<String> args, Path commandDir) {
        ProcessResult result = runGitAllowExitCodes(args, commandDir, Set.of(0));
        return result.output();
    }

    private ProcessResult runGitAllowExitCodes(List<String> args, Path commandDir, Set<Integer> allowedExitCodes) {
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
            int exitCode = process.exitValue();
            if (!allowedExitCodes.contains(exitCode)) {
                throw new IllegalStateException(
                    "Git command failed (exit " + exitCode + "): "
                        + String.join(" ", command) + ", output=" + output
                );
            }
            return new ProcessResult(exitCode, output);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to execute git command: " + String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted: " + String.join(" ", command), ex);
        }
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

    private static String normalizeRelativePrefix(String value, String defaultValue) {
        String normalized = value == null || value.isBlank() ? defaultValue : value.trim();
        normalized = normalized.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return defaultValue;
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("Relative prefix must not contain '..': " + value);
        }
        return normalized;
    }

    private String buildTaskBranch(String taskId) {
        String prefix = taskBranchPrefix.endsWith("/") ? taskBranchPrefix : taskBranchPrefix + "/";
        return prefix + taskId;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

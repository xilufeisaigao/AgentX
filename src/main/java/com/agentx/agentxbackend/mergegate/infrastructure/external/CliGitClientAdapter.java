package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.application.port.out.GitClientPort;
import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
public class CliGitClientAdapter implements GitClientPort {

    private static final Logger log = LoggerFactory.getLogger(CliGitClientAdapter.class);

    private final String gitExecutable;
    private final Path repoRoot;
    private final String mainBranch;
    private final String taskBranchPrefix;
    private final int commandTimeoutMs;

    public CliGitClientAdapter(
        @Value("${agentx.mergegate.git.executable:git}") String gitExecutable,
        @Value("${agentx.mergegate.git.repo-root:.}") String repoRoot,
        @Value("${agentx.mergegate.git.main-branch:main}") String mainBranch,
        @Value("${agentx.mergegate.git.task-branch-prefix:task/}") String taskBranchPrefix,
        @Value("${agentx.mergegate.git.command-timeout-ms:120000}") int commandTimeoutMs
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.mainBranch = mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch.trim();
        this.taskBranchPrefix = taskBranchPrefix == null ? "task/" : taskBranchPrefix;
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
    }

    @Override
    public String readMainHead() {
        return runGit(List.of("rev-parse", mainBranch)).trim();
    }

    @Override
    public MergeCandidate rebaseTaskBranch(String taskId, String mainHeadBefore) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedMainHeadBefore = requireNotBlank(mainHeadBefore, "mainHeadBefore");
        String taskBranch = buildTaskBranch(normalizedTaskId);

        runGit(List.of("show-ref", "--verify", "--quiet", "refs/heads/" + taskBranch));
        checkoutBranchWithRecovery(taskBranch);
        try {
            runGit(List.of("rebase", normalizedMainHeadBefore));
        } catch (IllegalStateException ex) {
            rollbackGitInProgressState();
            throw new IllegalStateException(
                "Git rebase failed for " + taskBranch + " onto " + normalizedMainHeadBefore
                    + ". Repository state was rolled back for retry. Cause: " + ex.getMessage(),
                ex
            );
        }
        String mergeCandidateCommit = runGit(List.of("rev-parse", "HEAD")).trim();

        return new MergeCandidate(
            normalizedTaskId,
            normalizedMainHeadBefore,
            mergeCandidateCommit
        );
    }

    @Override
    public void fastForwardMain(String mergeCandidateCommit) {
        String normalizedMergeCandidateCommit = requireNotBlank(mergeCandidateCommit, "mergeCandidateCommit");
        checkoutBranchWithRecovery(mainBranch);
        runGit(List.of("merge", "--ff-only", normalizedMergeCandidateCommit));
    }

    @Override
    public boolean recoverRepositoryIfNeeded() {
        boolean hasInProgressState = hasGitInProgressState();
        boolean hasUnmergedIndex = hasUnmergedIndexEntries();
        if (!hasInProgressState && !hasUnmergedIndex) {
            return false;
        }

        log.warn(
            "Detected interrupted git state, starting repository recovery. inProgressState={}, unmergedIndex={}",
            hasInProgressState,
            hasUnmergedIndex
        );
        rollbackGitInProgressState();
        checkoutBranchWithRecovery(mainBranch);

        if (hasGitInProgressState() || hasUnmergedIndexEntries()) {
            throw new IllegalStateException("Repository recovery did not clear interrupted git state.");
        }
        return true;
    }

    private boolean hasGitInProgressState() {
        String gitDirOutput = runGit(List.of("rev-parse", "--git-dir")).trim();
        Path gitDir = Path.of(gitDirOutput);
        if (!gitDir.isAbsolute()) {
            gitDir = repoRoot.resolve(gitDir).normalize();
        }
        return Files.exists(gitDir.resolve("MERGE_HEAD"))
            || Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))
            || Files.exists(gitDir.resolve("REVERT_HEAD"))
            || Files.exists(gitDir.resolve("REBASE_HEAD"))
            || Files.exists(gitDir.resolve("rebase-apply"))
            || Files.exists(gitDir.resolve("rebase-merge"));
    }

    private boolean hasUnmergedIndexEntries() {
        String output = runGit(List.of("ls-files", "-u"));
        return output != null && !output.trim().isEmpty();
    }

    private void checkoutBranchWithRecovery(String branchName) {
        try {
            runGit(List.of("checkout", branchName));
        } catch (IllegalStateException ex) {
            if (!isRecoverableCheckoutFailure(ex)) {
                throw ex;
            }
            log.warn("Git checkout failed due to interrupted index state, trying recovery. branch={}", branchName);
            rollbackGitInProgressState();
            runGit(List.of("checkout", branchName));
        }
    }

    private boolean isRecoverableCheckoutFailure(IllegalStateException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("resolve your current index first")
            || lower.contains("needs merge")
            || lower.contains("unmerged files");
    }

    private void rollbackGitInProgressState() {
        runGitBestEffort(List.of("rebase", "--abort"));
        runGitBestEffort(List.of("merge", "--abort"));
        runGitBestEffort(List.of("cherry-pick", "--abort"));
        runGitBestEffort(List.of("am", "--abort"));
        runGitBestEffort(List.of("reset", "--merge"));
    }

    private void runGitBestEffort(List<String> args) {
        List<String> command = buildCommand(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(repoRoot.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Skip git recovery command after timeout: {}", String.join(" ", command));
                return;
            }
            if (process.exitValue() == 0) {
                return;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug(
                "Ignore git recovery command failure (exit {}): {}, output={}",
                process.exitValue(),
                String.join(" ", command),
                output
            );
        } catch (IOException ex) {
            log.debug("Ignore git recovery command IO failure: {}", String.join(" ", command), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.debug("Ignore git recovery command interruption: {}", String.join(" ", command), ex);
        }
    }

    private List<String> buildCommand(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable);
        command.addAll(args);
        return command;
    }

    private String runGit(List<String> args) {
        List<String> command = buildCommand(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(repoRoot.toFile());
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

    private String buildTaskBranch(String taskId) {
        String prefix = taskBranchPrefix.endsWith("/") ? taskBranchPrefix : taskBranchPrefix + "/";
        return prefix + taskId;
    }

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}

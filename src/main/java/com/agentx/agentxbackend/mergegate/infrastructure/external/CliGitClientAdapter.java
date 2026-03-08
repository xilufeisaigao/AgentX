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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class CliGitClientAdapter implements GitClientPort {

    private static final Logger log = LoggerFactory.getLogger(CliGitClientAdapter.class);
    private static final String CANDIDATE_REF_PREFIX = "refs/agentx/candidate/";
    private static final String DELIVERY_TAG_PREFIX = "delivery/";
    private static final DateTimeFormatter DELIVERY_TAG_TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private final String gitExecutable;
    private final Path repoRoot;
    private final String sessionRepoPrefix;
    private final String mainBranch;
    private final String taskBranchPrefix;
    private final int commandTimeoutMs;

    public CliGitClientAdapter(
        @Value("${agentx.mergegate.git.executable:git}") String gitExecutable,
        @Value("${agentx.mergegate.git.repo-root:.}") String repoRoot,
        @Value("${agentx.mergegate.git.session-repo-prefix:sessions}") String sessionRepoPrefix,
        @Value("${agentx.mergegate.git.main-branch:main}") String mainBranch,
        @Value("${agentx.mergegate.git.task-branch-prefix:task/}") String taskBranchPrefix,
        @Value("${agentx.mergegate.git.command-timeout-ms:120000}") int commandTimeoutMs
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.sessionRepoPrefix = normalizeRelativePrefix(sessionRepoPrefix, "sessions");
        this.mainBranch = mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch.trim();
        this.taskBranchPrefix = taskBranchPrefix == null ? "task/" : taskBranchPrefix.trim();
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
    }

    @Override
    public String readMainHead(String sessionId) {
        Path sessionRepoRoot = resolveSessionRepoRoot(sessionId);
        return runGit(List.of("rev-parse", mainBranch), sessionRepoRoot).trim();
    }

    @Override
    public MergeCandidate rebaseTaskBranch(String sessionId, String taskId, String mainHeadBefore) {
        String normalizedTaskId = requireNotBlank(taskId, "taskId");
        String normalizedMainHeadBefore = requireNotBlank(mainHeadBefore, "mainHeadBefore");
        Path sessionRepoRoot = resolveSessionRepoRoot(sessionId);
        String taskBranch = buildTaskBranch(normalizedTaskId);

        runGit(List.of("show-ref", "--verify", "--quiet", "refs/heads/" + taskBranch), sessionRepoRoot);
        checkoutBranchWithRecovery(sessionRepoRoot, taskBranch);
        try {
            runGit(List.of("rebase", normalizedMainHeadBefore), sessionRepoRoot);
        } catch (IllegalStateException ex) {
            rollbackGitInProgressState(sessionRepoRoot);
            throw new IllegalStateException(
                "Git rebase failed for "
                    + taskBranch
                    + " onto "
                    + normalizedMainHeadBefore
                    + " in session "
                    + sessionId
                    + ". Repository state was rolled back for retry. Cause: "
                    + ex.getMessage(),
                ex
            );
        }
        String mergeCandidateCommit = runGit(List.of("rev-parse", "HEAD"), sessionRepoRoot).trim();
        String evidenceRef = createCandidateEvidenceRef(sessionRepoRoot, normalizedTaskId, mergeCandidateCommit);

        return new MergeCandidate(
            normalizedTaskId,
            normalizedMainHeadBefore,
            mergeCandidateCommit,
            evidenceRef
        );
    }

    @Override
    public void fastForwardMain(String sessionId, String mergeCandidateCommit) {
        String normalizedMergeCandidateCommit = requireNotBlank(mergeCandidateCommit, "mergeCandidateCommit");
        Path sessionRepoRoot = resolveSessionRepoRoot(sessionId);
        checkoutBranchWithRecovery(sessionRepoRoot, mainBranch);
        runGit(List.of("merge", "--ff-only", normalizedMergeCandidateCommit), sessionRepoRoot);
    }

    @Override
    public void ensureDeliveryTagOnMain(String sessionId, String mergeCandidateCommit) {
        String normalizedMergeCandidateCommit = requireNotBlank(mergeCandidateCommit, "mergeCandidateCommit");
        Path sessionRepoRoot = resolveSessionRepoRoot(sessionId);
        checkoutBranchWithRecovery(sessionRepoRoot, mainBranch);
        if (!listDeliveryTagsOnMain(sessionRepoRoot).isEmpty()) {
            return;
        }

        String tagName = DELIVERY_TAG_PREFIX + DELIVERY_TAG_TIME_FORMATTER.format(Instant.now());
        String message = "AgentX delivery tag on main for commit " + normalizedMergeCandidateCommit;
        if (tagExists(sessionRepoRoot, tagName)) {
            return;
        }
        runGit(
            List.of("tag", "-a", tagName, normalizedMergeCandidateCommit, "-m", message),
            sessionRepoRoot
        );
    }

    @Override
    public boolean recoverRepositoryIfNeeded() {
        List<Path> sessionRepos = listSessionRepoRoots();
        boolean recoveredAny = false;
        for (Path sessionRepo : sessionRepos) {
            boolean hasInProgressState = hasGitInProgressState(sessionRepo);
            boolean hasUnmergedIndex = hasUnmergedIndexEntries(sessionRepo);
            if (!hasInProgressState && !hasUnmergedIndex) {
                continue;
            }
            log.warn(
                "Detected interrupted git state, starting repository recovery. repo={}, inProgressState={}, unmergedIndex={}",
                sessionRepo,
                hasInProgressState,
                hasUnmergedIndex
            );
            rollbackGitInProgressState(sessionRepo);
            checkoutBranchWithRecovery(sessionRepo, mainBranch);

            if (hasGitInProgressState(sessionRepo) || hasUnmergedIndexEntries(sessionRepo)) {
                throw new IllegalStateException("Repository recovery did not clear interrupted git state: " + sessionRepo);
            }
            recoveredAny = true;
        }
        return recoveredAny;
    }

    private List<Path> listSessionRepoRoots() {
        Path sessionRoot = repoRoot.resolve(sessionRepoPrefix);
        if (!Files.exists(sessionRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(sessionRoot)) {
            return stream
                .map(path -> path.resolve("repo"))
                .filter(path -> Files.exists(path.resolve(".git")))
                .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to list session repositories under: " + sessionRoot, ex);
        }
    }

    private String createCandidateEvidenceRef(Path sessionRepoRoot, String taskId, String mergeCandidateCommit) {
        String safeTaskId = taskId
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._\\-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (safeTaskId.isBlank()) {
            safeTaskId = "task";
        }
        String attemptRef = CANDIDATE_REF_PREFIX + safeTaskId + "/" + Instant.now().toEpochMilli() + "-" + System.nanoTime();
        String latestRef = CANDIDATE_REF_PREFIX + safeTaskId + "/latest";
        runGit(List.of("update-ref", attemptRef, mergeCandidateCommit), sessionRepoRoot);
        runGit(List.of("update-ref", latestRef, mergeCandidateCommit), sessionRepoRoot);
        return attemptRef;
    }

    private List<String> listDeliveryTagsOnMain(Path sessionRepoRoot) {
        String output = runGit(
            List.of("tag", "--list", DELIVERY_TAG_PREFIX + "*", "--merged", mainBranch),
            sessionRepoRoot
        );
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return output.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
    }

    private boolean tagExists(Path sessionRepoRoot, String tagName) {
        try {
            runGit(List.of("rev-parse", "--verify", "refs/tags/" + tagName), sessionRepoRoot);
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private Path resolveSessionRepoRoot(String sessionId) {
        String normalizedSessionId = requireNotBlank(sessionId, "sessionId");
        String safeSessionId = normalizedSessionId
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._\\-]+", "-")
            .replaceAll("^-+|-+$", "");
        if (safeSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId has no safe characters: " + sessionId);
        }
        Path sessionRepoRoot = repoRoot
            .resolve(sessionRepoPrefix)
            .resolve(safeSessionId)
            .resolve("repo")
            .toAbsolutePath()
            .normalize();
        if (!sessionRepoRoot.startsWith(repoRoot)) {
            throw new IllegalArgumentException("session repo path escapes repo root: " + sessionId);
        }
        if (!Files.exists(sessionRepoRoot.resolve(".git"))) {
            throw new IllegalStateException("Session repo is not initialized: " + sessionRepoRoot);
        }
        return sessionRepoRoot;
    }

    private boolean hasGitInProgressState(Path sessionRepoRoot) {
        String gitDirOutput = runGit(List.of("rev-parse", "--git-dir"), sessionRepoRoot).trim();
        Path gitDir = Path.of(gitDirOutput);
        if (!gitDir.isAbsolute()) {
            gitDir = sessionRepoRoot.resolve(gitDir).normalize();
        }
        return Files.exists(gitDir.resolve("MERGE_HEAD"))
            || Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))
            || Files.exists(gitDir.resolve("REVERT_HEAD"))
            || Files.exists(gitDir.resolve("REBASE_HEAD"))
            || Files.exists(gitDir.resolve("rebase-apply"))
            || Files.exists(gitDir.resolve("rebase-merge"));
    }

    private boolean hasUnmergedIndexEntries(Path sessionRepoRoot) {
        String output = runGit(List.of("ls-files", "-u"), sessionRepoRoot);
        return output != null && !output.trim().isEmpty();
    }

    private void checkoutBranchWithRecovery(Path sessionRepoRoot, String branchName) {
        try {
            runGit(List.of("checkout", branchName), sessionRepoRoot);
        } catch (IllegalStateException ex) {
            if (!isRecoverableCheckoutFailure(ex)) {
                throw ex;
            }
            log.warn(
                "Git checkout failed due to interrupted index state, trying recovery. repo={}, branch={}",
                sessionRepoRoot,
                branchName
            );
            rollbackGitInProgressState(sessionRepoRoot);
            runGit(List.of("checkout", branchName), sessionRepoRoot);
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

    private void rollbackGitInProgressState(Path sessionRepoRoot) {
        runGitBestEffort(List.of("rebase", "--abort"), sessionRepoRoot);
        runGitBestEffort(List.of("merge", "--abort"), sessionRepoRoot);
        runGitBestEffort(List.of("cherry-pick", "--abort"), sessionRepoRoot);
        runGitBestEffort(List.of("am", "--abort"), sessionRepoRoot);
        runGitBestEffort(List.of("reset", "--merge"), sessionRepoRoot);
    }

    private void runGitBestEffort(List<String> args, Path sessionRepoRoot) {
        List<String> command = buildCommand(args);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(sessionRepoRoot.toFile());
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

    private String runGit(List<String> args, Path commandDir) {
        List<String> command = buildCommand(args);
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
}

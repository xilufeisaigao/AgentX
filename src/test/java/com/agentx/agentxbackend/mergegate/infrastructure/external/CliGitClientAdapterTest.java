package com.agentx.agentxbackend.mergegate.infrastructure.external;

import com.agentx.agentxbackend.mergegate.domain.model.MergeCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CliGitClientAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void rebaseTaskBranchShouldRecoverFromConflictForNextAttempt() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root");
        Path sessionRepo = repoRoot.resolve("sessions/SES-1/repo");
        Files.createDirectories(sessionRepo);

        runGit(sessionRepo, List.of("init"));
        runGit(sessionRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sessionRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sessionRepo.resolve("pom.xml"), "<project><name>base</name></project>\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "pom.xml"));
        runGit(sessionRepo, List.of("commit", "-m", "init"));
        runGit(sessionRepo, List.of("branch", "-M", "main"));

        runGit(sessionRepo, List.of("checkout", "-b", "task/TASK-1"));
        Files.writeString(sessionRepo.resolve("pom.xml"), "<project><name>task-one</name></project>\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "pom.xml"));
        runGit(sessionRepo, List.of("commit", "-m", "task one changes pom"));

        runGit(sessionRepo, List.of("checkout", "main"));
        Files.writeString(sessionRepo.resolve("pom.xml"), "<project><name>main</name></project>\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "pom.xml"));
        runGit(sessionRepo, List.of("commit", "-m", "main changes pom"));
        String mainHead = runGit(sessionRepo, List.of("rev-parse", "main")).trim();

        runGit(sessionRepo, List.of("checkout", "-b", "task/TASK-2"));
        Files.writeString(sessionRepo.resolve("README-task2.md"), "task 2\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README-task2.md"));
        runGit(sessionRepo, List.of("commit", "-m", "task two non-conflict"));
        runGit(sessionRepo, List.of("checkout", "main"));

        CliGitClientAdapter adapter = new CliGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            30000
        );

        assertThrows(IllegalStateException.class, () -> adapter.rebaseTaskBranch("SES-1", "TASK-1", mainHead));

        String unresolved = runGit(sessionRepo, List.of("diff", "--name-only", "--diff-filter=U")).trim();
        assertTrue(unresolved.isEmpty(), "conflict files should be cleaned after rebase failure");

        MergeCandidate candidate = adapter.rebaseTaskBranch("SES-1", "TASK-2", mainHead);
        assertEquals("TASK-2", candidate.taskId());
        assertEquals(mainHead, candidate.mainHeadBefore());
        assertFalse(candidate.mergeCandidateCommit().isBlank());
        assertTrue(candidate.evidenceRef().startsWith("refs/agentx/candidate/"));
    }

    @Test
    void recoverRepositoryIfNeededShouldAbortInterruptedRebaseAndCheckoutMain() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root-recover");
        Path sessionRepo = repoRoot.resolve("sessions/SES-9/repo");
        Files.createDirectories(sessionRepo);

        runGit(sessionRepo, List.of("init"));
        runGit(sessionRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sessionRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sessionRepo.resolve("README.md"), "base\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "init"));
        runGit(sessionRepo, List.of("branch", "-M", "main"));

        runGit(sessionRepo, List.of("checkout", "-b", "task/TASK-9"));
        Files.writeString(sessionRepo.resolve("README.md"), "task version\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "task change"));

        runGit(sessionRepo, List.of("checkout", "main"));
        Files.writeString(sessionRepo.resolve("README.md"), "main version\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "main change"));

        runGit(sessionRepo, List.of("checkout", "task/TASK-9"));
        CommandResult rebaseResult = runGitAllowFailure(sessionRepo, List.of("rebase", "main"));
        assertTrue(rebaseResult.exitCode() != 0, "rebase should fail to leave interrupted state");

        CliGitClientAdapter adapter = new CliGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            30000
        );
        assertTrue(adapter.recoverRepositoryIfNeeded());

        String unresolved = runGit(sessionRepo, List.of("diff", "--name-only", "--diff-filter=U")).trim();
        assertTrue(unresolved.isEmpty(), "unmerged files should be cleared");
        String currentBranch = runGit(sessionRepo, List.of("rev-parse", "--abbrev-ref", "HEAD")).trim();
        assertEquals("main", currentBranch);
        assertFalse(adapter.recoverRepositoryIfNeeded(), "second recovery should become no-op");
    }

    @Test
    void ensureDeliveryTagOnMainShouldCreateAnnotatedTagOnce() throws Exception {
        assumeTrue(canRunGit(), "git executable is required");
        Path repoRoot = tempDir.resolve("repo-root-tag");
        Path sessionRepo = repoRoot.resolve("sessions/SES-TAG/repo");
        Files.createDirectories(sessionRepo);

        runGit(sessionRepo, List.of("init"));
        runGit(sessionRepo, List.of("config", "user.email", "agentx@test.local"));
        runGit(sessionRepo, List.of("config", "user.name", "AgentX Test"));
        Files.writeString(sessionRepo.resolve("README.md"), "base\n", StandardCharsets.UTF_8);
        runGit(sessionRepo, List.of("add", "README.md"));
        runGit(sessionRepo, List.of("commit", "-m", "init"));
        runGit(sessionRepo, List.of("branch", "-M", "main"));
        String head = runGit(sessionRepo, List.of("rev-parse", "main")).trim();

        CliGitClientAdapter adapter = new CliGitClientAdapter(
            "git",
            repoRoot.toString(),
            "sessions",
            "main",
            "task/",
            30000
        );

        adapter.ensureDeliveryTagOnMain("SES-TAG", head);
        adapter.ensureDeliveryTagOnMain("SES-TAG", head);

        String tags = runGit(sessionRepo, List.of("tag", "--list", "delivery/*", "--merged", "main")).trim();
        assertFalse(tags.isBlank(), "delivery tag should exist on main");
        long tagCount = tags.lines().count();
        assertEquals(1L, tagCount, "delivery tag creation should be idempotent");
    }

    private static boolean canRunGit() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String runGit(Path repo, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(args));
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git command timeout: " + args);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git command failed " + args + ", output=" + output);
        }
        return output;
    }

    private static CommandResult runGitAllowFailure(Path repo, List<String> args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(args));
        builder.directory(repo.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git command timeout: " + args);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private static List<String> buildCommand(List<String> args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(args);
        return command;
    }

    private record CommandResult(int exitCode, String output) {
    }
}

package com.agentx.platform.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class TestGitRepoHelper {

    private TestGitRepoHelper() {
    }

    public static void resetFixtureRepository(Path repoRoot) {
        if (Files.exists(repoRoot.resolve(".git"))) {
            resetExistingFixtureRepository(repoRoot);
            return;
        }
        deleteRecursively(repoRoot);
        try {
            Files.createDirectories(repoRoot);
            Files.createDirectories(repoRoot.resolve("src/main/java"));
            Files.createDirectories(repoRoot.resolve("src/test/java"));
            Files.writeString(repoRoot.resolve("README.md"), "fixture repo\n", StandardCharsets.UTF_8);
            Files.writeString(repoRoot.resolve("src/main/java/App.java"), "class App {}\n", StandardCharsets.UTF_8);
            Files.writeString(repoRoot.resolve("src/test/java/AppTest.java"), "class AppTest {}\n", StandardCharsets.UTF_8);
            run(repoRoot.getParent(), List.of("git", "init", "--initial-branch=main", repoRoot.toString()));
            run(repoRoot, List.of("git", "config", "user.email", "fixture@example.local"));
            run(repoRoot, List.of("git", "config", "user.name", "Fixture Repo"));
            run(repoRoot, List.of("git", "add", "."));
            run(repoRoot, List.of("git", "commit", "-m", "initial commit"));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to reset fixture repository", exception);
        }
    }

    public static void cleanDirectory(Path directory) {
        deleteRecursively(directory);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to recreate " + directory, exception);
        }
    }

    public static void run(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .start();
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "command failed with exit " + exitCode + ": " + String.join(" ", command)
                                + System.lineSeparator()
                                + "stdout: " + stdout
                                + System.lineSeparator()
                                + "stderr: " + stderr
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute command: " + String.join(" ", command), exception);
        }
    }

    private static String readFully(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    public static void deleteRecursively(Path path) {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(candidate -> {
                        try {
                            deleteWithRetry(candidate);
                        } catch (IOException exception) {
                            throw new IllegalStateException("failed to delete " + candidate, exception);
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException("failed to clean path " + path, exception);
        }
    }

    private static void deleteWithRetry(Path candidate) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                Files.deleteIfExists(candidate);
                return;
            } catch (IOException exception) {
                lastException = exception;
                try {
                    Thread.sleep(150L * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while deleting " + candidate, interruptedException);
                }
            }
        }
        throw lastException;
    }

    private static void resetExistingFixtureRepository(Path repoRoot) {
        removeManagedWorktrees(repoRoot);
        run(repoRoot, List.of("git", "checkout", "--force", "main"));
        run(repoRoot, List.of("git", "reset", "--hard", "HEAD"));
        run(repoRoot, List.of("git", "clean", "-fdx"));
        deleteManagedBranches(repoRoot);
    }

    private static void removeManagedWorktrees(Path repoRoot) {
        run(repoRoot, List.of("git", "worktree", "prune"));
        String output = runAndCapture(repoRoot, List.of("git", "worktree", "list", "--porcelain"));
        for (String worktreePath : parseWorktreePaths(output)) {
            Path candidate = Path.of(worktreePath).toAbsolutePath().normalize();
            if (candidate.equals(repoRoot.toAbsolutePath().normalize())) {
                continue;
            }
            run(repoRoot, List.of("git", "worktree", "remove", "--force", candidate.toString()));
        }
        run(repoRoot, List.of("git", "worktree", "prune"));
    }

    private static void deleteManagedBranches(Path repoRoot) {
        String output = runAndCapture(
                repoRoot,
                List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/heads")
        );
        for (String branch : output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
            String lowerBranch = branch.toLowerCase(Locale.ROOT);
            if ("main".equals(lowerBranch)) {
                continue;
            }
            if (lowerBranch.startsWith("task/") || lowerBranch.startsWith("merge/")) {
                run(repoRoot, List.of("git", "branch", "-D", branch));
            }
        }
    }

    private static List<String> parseWorktreePaths(String output) {
        return output.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("worktree "))
                .map(line -> line.substring("worktree ".length()).trim())
                .toList();
    }

    private static String runAndCapture(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .start();
            String stdout = readFully(process.getInputStream());
            String stderr = readFully(process.getErrorStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "command failed with exit " + exitCode + ": " + String.join(" ", command)
                                + System.lineSeparator()
                                + "stdout: " + stdout
                                + System.lineSeparator()
                                + "stderr: " + stderr
                );
            }
            return stdout;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("command interrupted: " + String.join(" ", command), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute command: " + String.join(" ", command), exception);
        }
    }
}

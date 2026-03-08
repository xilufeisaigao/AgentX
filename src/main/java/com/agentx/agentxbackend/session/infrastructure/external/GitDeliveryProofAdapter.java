package com.agentx.agentxbackend.session.infrastructure.external;

import com.agentx.agentxbackend.session.application.port.out.DeliveryProofPort;
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
public class GitDeliveryProofAdapter implements DeliveryProofPort {

    private static final String DELIVERY_TAG_PREFIX = "delivery/";

    private final String gitExecutable;
    private final Path repoRoot;
    private final String sessionRepoPrefix;
    private final String mainBranch;
    private final int commandTimeoutMs;

    public GitDeliveryProofAdapter(
        @Value("${agentx.session.delivery-proof.git-executable:${agentx.mergegate.git.executable:git}}")
        String gitExecutable,
        @Value("${agentx.session.delivery-proof.repo-root:${agentx.workspace.git.repo-root:.}}")
        String repoRoot,
        @Value("${agentx.session.delivery-proof.session-repo-prefix:${agentx.workspace.git.session-repo-prefix:sessions}}")
        String sessionRepoPrefix,
        @Value("${agentx.session.delivery-proof.main-branch:${agentx.mergegate.git.main-branch:main}}")
        String mainBranch,
        @Value("${agentx.session.delivery-proof.command-timeout-ms:120000}")
        int commandTimeoutMs
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.repoRoot = Path.of(repoRoot == null || repoRoot.isBlank() ? "." : repoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.sessionRepoPrefix = normalizeRelativePrefix(sessionRepoPrefix, "sessions");
        this.mainBranch = mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch.trim();
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
    }

    @Override
    public boolean hasAtLeastOneDeliveryTagOnMain(String sessionId) {
        Path sessionRepoRoot = resolveSessionRepoRoot(sessionId);
        if (!Files.exists(sessionRepoRoot.resolve(".git"))) {
            return false;
        }
        String output = runGit(
            List.of("tag", "--list", DELIVERY_TAG_PREFIX + "*", "--merged", mainBranch),
            sessionRepoRoot
        );
        if (output == null || output.isBlank()) {
            return false;
        }
        for (String rawLine : output.split("\\R")) {
            String tagName = rawLine == null ? "" : rawLine.trim();
            if (!isDeliveryTagFormat(tagName)) {
                continue;
            }
            if (isAnnotatedTag(sessionRepoRoot, tagName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAnnotatedTag(Path sessionRepoRoot, String tagName) {
        String output = runGit(
            List.of("cat-file", "-t", "refs/tags/" + tagName),
            sessionRepoRoot
        );
        return output != null && "tag".equalsIgnoreCase(output.trim());
    }

    private static boolean isDeliveryTagFormat(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return false;
        }
        if (!tagName.startsWith(DELIVERY_TAG_PREFIX)) {
            return false;
        }
        String suffix = tagName.substring(DELIVERY_TAG_PREFIX.length());
        return suffix.matches("\\d{8}-\\d{4}([._\\-a-zA-Z0-9]+)?");
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
        return sessionRepoRoot;
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

    private static String requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}

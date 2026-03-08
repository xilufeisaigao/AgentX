package com.agentx.agentxbackend.delivery.infrastructure.external;

import com.agentx.agentxbackend.delivery.application.port.out.DeliveryCloneRepositoryPort;
import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class GitBareCloneRepositoryAdapter implements DeliveryCloneRepositoryPort {

    private static final Pattern UNSAFE_REPO_NAME_CHARS = Pattern.compile("[^A-Za-z0-9._-]");
    private static final String METADATA_SESSION_ID = "session_id";
    private static final String METADATA_REPO_NAME = "repo_name";
    private static final String METADATA_PUBLISHED_AT_EPOCH_MS = "published_at_epoch_ms";
    private static final String METADATA_EXPIRES_AT_EPOCH_MS = "expires_at_epoch_ms";

    private final String gitExecutable;
    private final Path workspaceRepoRoot;
    private final String sessionRepoPrefix;
    private final Path cloneRepoRoot;
    private final Path metadataRoot;
    private final String publicBase;
    private final String repoPrefix;
    private final String mainBranch;
    private final int commandTimeoutMs;
    private final Duration retention;

    public GitBareCloneRepositoryAdapter(
        @Value("${agentx.delivery.clone-publish.git-executable:git}") String gitExecutable,
        @Value("${agentx.delivery.clone-publish.repo-root:${agentx.workspace.git.repo-root:.}}") String sourceRepoRoot,
        @Value("${agentx.delivery.clone-publish.session-repo-prefix:${agentx.workspace.git.session-repo-prefix:sessions}}")
        String sessionRepoPrefix,
        @Value("${agentx.delivery.clone-publish.remote-root:}") String cloneRepoRoot,
        @Value("${agentx.delivery.clone-publish.public-base:}") String publicBase,
        @Value("${agentx.delivery.clone-publish.repo-prefix:agentx-session-}") String repoPrefix,
        @Value("${agentx.delivery.clone-publish.main-branch:main}") String mainBranch,
        @Value("${agentx.delivery.clone-publish.command-timeout-ms:120000}") int commandTimeoutMs,
        @Value("${agentx.delivery.clone-publish.retention-hours:72}") long retentionHours
    ) {
        this.gitExecutable = gitExecutable == null || gitExecutable.isBlank() ? "git" : gitExecutable.trim();
        this.workspaceRepoRoot = Path.of(sourceRepoRoot == null || sourceRepoRoot.isBlank() ? "." : sourceRepoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.sessionRepoPrefix = normalizeRelativePrefix(sessionRepoPrefix, "sessions");
        Path defaultCloneRoot = this.workspaceRepoRoot.resolve("remotes");
        this.cloneRepoRoot = Path.of(cloneRepoRoot == null || cloneRepoRoot.isBlank()
                ? defaultCloneRoot.toString()
                : cloneRepoRoot.trim())
            .toAbsolutePath()
            .normalize();
        this.metadataRoot = this.cloneRepoRoot.resolve(".metadata");
        this.publicBase = publicBase == null ? "" : publicBase.trim();
        this.repoPrefix = repoPrefix == null || repoPrefix.isBlank() ? "agentx-session-" : repoPrefix.trim();
        this.mainBranch = mainBranch == null || mainBranch.isBlank() ? "main" : mainBranch.trim();
        this.commandTimeoutMs = Math.max(5_000, commandTimeoutMs);
        this.retention = Duration.ofHours(Math.max(1L, retentionHours));
    }

    @Override
    public DeliveryClonePublication publish(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        Path sourceRepoRoot = resolveSessionRepoPath(normalizedSessionId);
        ensureSourceRepoReady(sourceRepoRoot);
        try {
            Files.createDirectories(cloneRepoRoot);
            Files.createDirectories(metadataRoot);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to prepare clone repository directories", ex);
        }

        String repoName = buildRepoName(normalizedSessionId);
        Path targetRepoPath = cloneRepoRoot.resolve(repoName);

        if (Files.exists(targetRepoPath)) {
            mirrorFetch(targetRepoPath, sourceRepoRoot);
        } else {
            cloneBare(targetRepoPath, sourceRepoRoot);
        }
        pinHeadToMainIfPresent(targetRepoPath);

        Instant publishedAt = Instant.now();
        Instant expiresAt = publishedAt.plus(retention);
        writeMetadata(
            normalizedSessionId,
            repoName,
            publishedAt,
            expiresAt
        );
        return buildPublication(normalizedSessionId, repoName, targetRepoPath, publishedAt, expiresAt);
    }

    @Override
    public Optional<DeliveryClonePublication> findActive(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        Path metadataFile = metadataRoot.resolve(normalizedSessionId + ".properties");
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }
        Properties properties = loadMetadata(metadataFile);
        String repoName = properties.getProperty(METADATA_REPO_NAME, "");
        if (repoName.isBlank()) {
            return Optional.empty();
        }
        Instant publishedAt = parseEpochInstant(properties.getProperty(METADATA_PUBLISHED_AT_EPOCH_MS));
        Instant expiresAt = parseEpochInstant(properties.getProperty(METADATA_EXPIRES_AT_EPOCH_MS));
        if (publishedAt == null || expiresAt == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(expiresAt)) {
            return Optional.empty();
        }
        Path targetRepoPath = cloneRepoRoot.resolve(repoName);
        if (!Files.exists(targetRepoPath)) {
            return Optional.empty();
        }
        return Optional.of(buildPublication(normalizedSessionId, repoName, targetRepoPath, publishedAt, expiresAt));
    }

    @Override
    public int cleanupExpired() {
        Instant now = Instant.now();
        int deleted = 0;
        Set<String> referencedRepoNames = new HashSet<>();

        if (Files.exists(metadataRoot)) {
            try (Stream<Path> stream = Files.list(metadataRoot)) {
                for (Path metadataFile : stream.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
                    Properties properties = loadMetadata(metadataFile);
                    String repoName = properties.getProperty(METADATA_REPO_NAME, "");
                    if (!repoName.isBlank()) {
                        referencedRepoNames.add(repoName);
                    }
                    Instant expiresAt = parseEpochInstant(properties.getProperty(METADATA_EXPIRES_AT_EPOCH_MS));
                    if (expiresAt == null || now.isAfter(expiresAt)) {
                        if (!repoName.isBlank()) {
                            deleteDirectoryIfExists(cloneRepoRoot.resolve(repoName));
                        }
                        deleteFileIfExists(metadataFile);
                        deleted++;
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to cleanup expired clone repositories", ex);
            }
        }

        if (Files.exists(cloneRepoRoot)) {
            try (Stream<Path> stream = Files.list(cloneRepoRoot)) {
                for (Path repoPath : stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().equals(".metadata"))
                    .filter(path -> path.getFileName().toString().endsWith(".git"))
                    .toList()) {
                    String repoName = repoPath.getFileName().toString();
                    if (referencedRepoNames.contains(repoName)) {
                        continue;
                    }
                    FileTime lastModifiedTime = Files.getLastModifiedTime(repoPath);
                    if (lastModifiedTime.toInstant().isBefore(now.minus(retention))) {
                        deleteDirectoryIfExists(repoPath);
                        deleted++;
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to cleanup stale clone repositories", ex);
            }
        }
        return deleted;
    }

    private DeliveryClonePublication buildPublication(
        String sessionId,
        String repoName,
        Path targetRepoPath,
        Instant publishedAt,
        Instant expiresAt
    ) {
        String cloneUrl = buildCloneUrl(targetRepoPath, repoName);
        return new DeliveryClonePublication(
            sessionId,
            repoName,
            cloneUrl,
            publishedAt,
            expiresAt
        );
    }

    private void ensureSourceRepoReady(Path sourceRepoRoot) {
        Path gitDir = sourceRepoRoot.resolve(".git");
        if (!Files.exists(gitDir)) {
            throw new IllegalStateException("Source repository is not initialized: " + sourceRepoRoot);
        }
        runGit(List.of("-C", sourceRepoRoot.toString(), "rev-parse", "--verify", "HEAD"), sourceRepoRoot, Set.of(0));
    }

    private void cloneBare(Path targetRepoPath, Path sourceRepoRoot) {
        runGit(
            List.of("clone", "--bare", sourceRepoRoot.toString(), targetRepoPath.toString()),
            cloneRepoRoot,
            Set.of(0)
        );
    }

    private void mirrorFetch(Path targetRepoPath, Path sourceRepoRoot) {
        runGit(
            List.of(
                "--git-dir",
                targetRepoPath.toString(),
                "fetch",
                "--prune",
                "--force",
                sourceRepoRoot.toString(),
                "+refs/heads/*:refs/heads/*",
                "+refs/tags/*:refs/tags/*"
            ),
            cloneRepoRoot,
            Set.of(0)
        );
    }

    private Path resolveSessionRepoPath(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        String safeSessionId = UNSAFE_REPO_NAME_CHARS.matcher(normalizedSessionId.toLowerCase()).replaceAll("-");
        if (safeSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is not valid for source repository resolution");
        }
        Path sessionRepoPath = workspaceRepoRoot
            .resolve(sessionRepoPrefix)
            .resolve(safeSessionId)
            .resolve("repo")
            .toAbsolutePath()
            .normalize();
        if (!sessionRepoPath.startsWith(workspaceRepoRoot)) {
            throw new IllegalArgumentException("Resolved source repository escapes workspace root: " + sessionId);
        }
        return sessionRepoPath;
    }

    private void pinHeadToMainIfPresent(Path targetRepoPath) {
        ProcessResult exists = runGit(
            List.of(
                "--git-dir",
                targetRepoPath.toString(),
                "show-ref",
                "--verify",
                "--quiet",
                "refs/heads/" + mainBranch
            ),
            cloneRepoRoot,
            Set.of(0, 1)
        );
        if (exists.exitCode() == 0) {
            runGit(
                List.of(
                    "--git-dir",
                    targetRepoPath.toString(),
                    "symbolic-ref",
                    "HEAD",
                    "refs/heads/" + mainBranch
                ),
                cloneRepoRoot,
                Set.of(0)
            );
        }
    }

    private void writeMetadata(
        String sessionId,
        String repoName,
        Instant publishedAt,
        Instant expiresAt
    ) {
        Properties properties = new Properties();
        properties.setProperty(METADATA_SESSION_ID, sessionId);
        properties.setProperty(METADATA_REPO_NAME, repoName);
        properties.setProperty(METADATA_PUBLISHED_AT_EPOCH_MS, String.valueOf(publishedAt.toEpochMilli()));
        properties.setProperty(METADATA_EXPIRES_AT_EPOCH_MS, String.valueOf(expiresAt.toEpochMilli()));

        Path metadataFile = metadataRoot.resolve(sessionId + ".properties");
        Path tempFile = metadataRoot.resolve(sessionId + ".properties.tmp");
        try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
            properties.store(outputStream, "AgentX delivery clone publication");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write delivery clone metadata: " + metadataFile, ex);
        }
        try {
            Files.move(
                tempFile,
                metadataFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist delivery clone metadata: " + metadataFile, ex);
        }
    }

    private Properties loadMetadata(Path metadataFile) {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(metadataFile)) {
            properties.load(inputStream);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read delivery clone metadata: " + metadataFile, ex);
        }
        return properties;
    }

    private String buildCloneUrl(Path targetRepoPath, String repoName) {
        if (publicBase.isBlank()) {
            return targetRepoPath.toUri().toString();
        }
        String base = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
        return base + "/" + repoName;
    }

    private ProcessResult runGit(List<String> args, Path workDir, Set<Integer> allowedExitCodes) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(gitExecutable);
        command.addAll(args);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Git command timeout: " + String.join(" ", command));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (!allowedExitCodes.contains(exitCode)) {
                throw new IllegalStateException(
                    "Git command failed (exit " + exitCode + "): " + String.join(" ", command) + ", output=" + output
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

    private String buildRepoName(String sessionId) {
        String sanitized = UNSAFE_REPO_NAME_CHARS.matcher(sessionId).replaceAll("-");
        if (sanitized.isBlank()) {
            throw new IllegalArgumentException("sessionId is not valid for clone repository naming");
        }
        return repoPrefix + sanitized + ".git";
    }

    private static String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId.trim();
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
            throw new IllegalArgumentException("relative prefix must not contain '..': " + value);
        }
        return normalized;
    }

    private static Instant parseEpochInstant(String rawEpochMillis) {
        if (rawEpochMillis == null || rawEpochMillis.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(rawEpochMillis.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void deleteFileIfExists(Path filePath) {
        deletePathWithRetry(filePath);
    }

    private static void deleteDirectoryIfExists(Path rootPath) {
        if (!Files.exists(rootPath)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(rootPath)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                deletePathWithRetry(path);
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to delete directory: " + rootPath, ex);
        }
    }

    private static void deletePathWithRetry(Path path) {
        if (path == null) {
            return;
        }
        clearReadOnlyIfPossible(path);
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (AccessDeniedException ex) {
                lastError = ex;
                sleepQuietly(40L * attempt);
                clearReadOnlyIfPossible(path);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to delete path: " + path, ex);
            }
        }
        throw new IllegalStateException("Failed to delete path: " + path, lastError);
    }

    private static void clearReadOnlyIfPossible(Path path) {
        try {
            path.toFile().setWritable(true, false);
        } catch (SecurityException ignored) {
            // best effort
        }
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (Exception ignored) {
            // not a DOS file system or attribute unsupported
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

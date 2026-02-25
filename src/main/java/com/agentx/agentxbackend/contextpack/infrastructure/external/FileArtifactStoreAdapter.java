package com.agentx.agentxbackend.contextpack.infrastructure.external;

import com.agentx.agentxbackend.contextpack.application.port.out.ArtifactStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Component
public class FileArtifactStoreAdapter implements ArtifactStorePort {

    private final Path absoluteRoot;
    private final String rootRef;

    public FileArtifactStoreAdapter(@Value("${agentx.contextpack.artifact-root:.agentx}") String rootPath) {
        String normalizedRoot = (rootPath == null || rootPath.isBlank()) ? ".agentx" : rootPath.trim();
        Path configuredRoot = Paths.get(normalizedRoot).normalize();
        this.absoluteRoot = configuredRoot.toAbsolutePath().normalize();
        String ref = configuredRoot.toString().replace('\\', '/');
        this.rootRef = ref.isBlank() ? ".agentx" : ref;
    }

    @Override
    public String store(String path, String content) {
        String normalizedPath = normalizeRelativePath(path);
        String safeContent = content == null ? "" : content;
        try {
            Path target = absoluteRoot.resolve(normalizedPath).normalize();
            if (!target.startsWith(absoluteRoot)) {
                throw new IllegalArgumentException("Artifact path escapes root: " + normalizedPath);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                target,
                safeContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            return "file:" + joinRefPath(rootRef, normalizedPath);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to store artifact at path " + normalizedPath, ex);
        }
    }

    private static String normalizeRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        String normalized = rawPath.replace('\\', '/').trim();
        if (normalized.startsWith("/") || normalized.matches("^[a-zA-Z]:/.*")) {
            throw new IllegalArgumentException("path must be relative");
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("path must not contain '..'");
        }
        return normalized;
    }

    private static String joinRefPath(String root, String child) {
        if (root.endsWith("/")) {
            return root + child;
        }
        return root + "/" + child;
    }
}

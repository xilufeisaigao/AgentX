package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.support.RuntimeInfrastructureProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Component
public class LocalRepoIndexService implements RepoIndexService, WorkflowOverlayIndexService {

    private final RuntimeInfrastructureProperties runtimeProperties;
    private final RetrievalProperties retrievalProperties;
    private final SymbolRetriever symbolRetriever;
    private final ChunkStore chunkStore;

    public LocalRepoIndexService(
            RuntimeInfrastructureProperties runtimeProperties,
            RetrievalProperties retrievalProperties,
            SymbolRetriever symbolRetriever,
            ChunkStore chunkStore
    ) {
        this.runtimeProperties = runtimeProperties;
        this.retrievalProperties = retrievalProperties;
        this.symbolRetriever = symbolRetriever;
        this.chunkStore = chunkStore;
    }

    @Override
    public RepoIndexManifest buildBaseIndex() {
        return buildIndex("base-repo", runtimeProperties.requiredRepoRoot(), false);
    }

    @Override
    public RepoIndexManifest buildOverlayIndex(String workflowRunId, String taskId, Path overlayRoot) {
        if (overlayRoot == null) {
            return new RepoIndexManifest("overlay-empty-" + workflowRunId + "-" + taskId, "empty", "", List.of());
        }
        return buildIndex("overlay-" + workflowRunId + "-" + taskId, overlayRoot, true);
    }

    private RepoIndexManifest buildIndex(String indexIdPrefix, Path root, boolean overlay) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<IndexedChunk> chunks = new ArrayList<>();
        String fingerprint = fingerprint(normalizedRoot);
        try {
            Files.walk(normalizedRoot)
                    .filter(Files::isRegularFile)
                    .filter(path -> shouldIndex(normalizedRoot, path))
                    .forEach(path -> chunks.addAll(chunksForFile(normalizedRoot, path, overlay)));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to build repo index from " + normalizedRoot, exception);
        }
        return chunkStore.write(new RepoIndexManifest(
                indexIdPrefix + "-" + fingerprint.substring(0, 12),
                fingerprint,
                normalizedRoot.toString(),
                chunks
        ));
    }

    private boolean shouldIndex(Path root, Path path) {
        String relativePath = root.relativize(path).toString().replace('\\', '/');
        if (relativePath.startsWith(".git/") || relativePath.contains("/target/") || relativePath.contains("/build/")) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean extensionAllowed = retrievalProperties.getIndexedExtensions().stream().anyMatch(fileName::endsWith);
        if (!extensionAllowed) {
            return false;
        }
        try {
            return Files.size(path) <= retrievalProperties.getMaxFileSize();
        } catch (IOException exception) {
            return false;
        }
    }

    private List<IndexedChunk> chunksForFile(Path root, Path filePath, boolean overlay) {
        try {
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<String> symbols = symbolRetriever.symbolsFor(filePath, lines);
            List<IndexedChunk> chunks = new ArrayList<>();
            int chunkSize = Math.max(8, retrievalProperties.getChunkSize());
            for (int start = 0; start < lines.size(); start += chunkSize) {
                int endExclusive = Math.min(lines.size(), start + chunkSize);
                String text = String.join(System.lineSeparator(), lines.subList(start, endExclusive));
                chunks.add(new IndexedChunk(
                        chunkId(filePath, start, endExclusive),
                        filePath.getFileName().toString().endsWith(".java") ? "CODE" : "TEXT",
                        filePath.toString(),
                        root.relativize(filePath).toString().replace('\\', '/'),
                        start + 1,
                        endExclusive,
                        text,
                        symbols,
                        overlay
                ));
            }
            return chunks;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to chunk file " + filePath, exception);
        }
    }

    private String fingerprint(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                            digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                            digest.update(Long.toString(attributes.size()).getBytes(StandardCharsets.UTF_8));
                            digest.update(Long.toString(attributes.lastModifiedTime().toMillis()).getBytes(StandardCharsets.UTF_8));
                        } catch (IOException ignored) {
                            // Best-effort fingerprinting for local lexical index only.
                        }
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("failed to fingerprint index root " + root, exception);
        }
    }

    private String chunkId(Path filePath, int start, int endExclusive) {
        return Integer.toHexString((filePath.toString() + ":" + start + ":" + endExclusive).hashCode());
    }
}

package com.agentx.platform.runtime.retrieval;

import java.util.List;
import java.util.Objects;

public record IndexedChunk(
        String chunkId,
        String sourceType,
        String sourceRef,
        String relativePath,
        int startLine,
        int endLine,
        String text,
        List<String> symbols,
        boolean overlay
) {

    public IndexedChunk {
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        Objects.requireNonNull(text, "text must not be null");
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols must not be null"));
    }
}

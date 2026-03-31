package com.agentx.platform.runtime.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RetrievalSnippet(
        String snippetId,
        String sourceType,
        String sourceRef,
        String title,
        String excerpt,
        double score,
        List<String> symbols,
        Map<String, Object> metadata
) {

    public RetrievalSnippet {
        Objects.requireNonNull(snippetId, "snippetId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceRef, "sourceRef must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(excerpt, "excerpt must not be null");
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols must not be null"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}

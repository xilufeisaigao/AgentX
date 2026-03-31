package com.agentx.platform.runtime.retrieval;

import java.util.List;
import java.util.Objects;

public record RepoIndexManifest(
        String indexId,
        String fingerprint,
        String rootPath,
        List<IndexedChunk> chunks
) {

    public RepoIndexManifest {
        Objects.requireNonNull(indexId, "indexId must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(rootPath, "rootPath must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
    }
}

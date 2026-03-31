package com.agentx.platform.runtime.context;

import java.time.LocalDateTime;
import java.util.Objects;

public record CompiledContextPack(
        ContextPackType packType,
        ContextScope scope,
        String sourceFingerprint,
        String artifactRef,
        String contentJson,
        FactBundle factBundle,
        RetrievalBundle retrievalBundle,
        LocalDateTime compiledAt
) {

    public CompiledContextPack {
        Objects.requireNonNull(packType, "packType must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(sourceFingerprint, "sourceFingerprint must not be null");
        Objects.requireNonNull(artifactRef, "artifactRef must not be null");
        Objects.requireNonNull(contentJson, "contentJson must not be null");
        Objects.requireNonNull(factBundle, "factBundle must not be null");
        Objects.requireNonNull(retrievalBundle, "retrievalBundle must not be null");
        Objects.requireNonNull(compiledAt, "compiledAt must not be null");
    }
}

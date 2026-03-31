package com.agentx.platform.runtime.retrieval;

import java.util.List;
import java.util.Objects;

public record RetrievalQuery(
        List<String> terms,
        List<String> preferredPaths
) {

    public RetrievalQuery {
        terms = List.copyOf(Objects.requireNonNull(terms, "terms must not be null"));
        preferredPaths = List.copyOf(Objects.requireNonNull(preferredPaths, "preferredPaths must not be null"));
    }
}

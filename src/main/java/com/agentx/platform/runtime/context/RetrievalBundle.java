package com.agentx.platform.runtime.context;

import java.util.List;
import java.util.Objects;

public record RetrievalBundle(
        List<RetrievalSnippet> snippets
) {

    public RetrievalBundle {
        snippets = List.copyOf(Objects.requireNonNull(snippets, "snippets must not be null"));
    }
}

package com.agentx.platform.runtime.agentruntime;

import java.nio.file.Path;
import java.util.Objects;

public record ContainerMount(
        Path sourcePath,
        String targetPath,
        boolean readOnly
) {

    public ContainerMount {
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");
    }
}

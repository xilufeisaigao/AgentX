package com.agentx.platform.runtime.agentruntime;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContainerLaunchSpec(
        String containerName,
        String image,
        String workingDirectory,
        List<String> command,
        List<ContainerMount> mounts,
        Map<String, String> environment,
        Duration timeout
) {

    public ContainerLaunchSpec {
        Objects.requireNonNull(containerName, "containerName must not be null");
        Objects.requireNonNull(image, "image must not be null");
        Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        command = List.copyOf(Objects.requireNonNull(command, "command must not be null"));
        mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts must not be null"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        Objects.requireNonNull(timeout, "timeout must not be null");
    }
}

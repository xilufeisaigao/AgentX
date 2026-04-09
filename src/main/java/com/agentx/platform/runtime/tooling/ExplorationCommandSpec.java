package com.agentx.platform.runtime.tooling;

import java.util.List;
import java.util.Objects;

public record ExplorationCommandSpec(
        String commandId,
        List<String> argvTemplate,
        List<String> allowedArgs,
        String description
) {

    public ExplorationCommandSpec {
        Objects.requireNonNull(commandId, "commandId must not be null");
        argvTemplate = List.copyOf(Objects.requireNonNull(argvTemplate, "argvTemplate must not be null"));
        allowedArgs = List.copyOf(Objects.requireNonNull(allowedArgs, "allowedArgs must not be null"));
        description = description == null ? "" : description;
    }
}

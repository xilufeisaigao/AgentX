package com.agentx.agentxbackend.query.api;

import com.agentx.agentxbackend.query.application.port.in.ProgressQueryUseCase;

public class ProgressQueryController {

    private final ProgressQueryUseCase useCase;

    public ProgressQueryController(ProgressQueryUseCase useCase) {
        this.useCase = useCase;
    }
}

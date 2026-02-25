package com.agentx.agentxbackend.delivery.application;

import com.agentx.agentxbackend.delivery.application.port.in.DeliveryProofUseCase;
import com.agentx.agentxbackend.delivery.application.port.out.GitTagPort;

public class DeliveryService implements DeliveryProofUseCase {

    private final GitTagPort gitTagPort;

    public DeliveryService(GitTagPort gitTagPort) {
        this.gitTagPort = gitTagPort;
    }

    @Override
    public boolean hasAtLeastOneDeliveryTagOnMain(String sessionId) {
        return !gitTagPort.listDeliveryTagsOnMain(sessionId).isEmpty();
    }
}

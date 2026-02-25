package com.agentx.agentxbackend.delivery.application.port.in;

public interface DeliveryProofUseCase {

    boolean hasAtLeastOneDeliveryTagOnMain(String sessionId);
}

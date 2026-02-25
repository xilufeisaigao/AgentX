package com.agentx.agentxbackend.session.application.port.out;

public interface DeliveryProofPort {

    boolean hasAtLeastOneDeliveryTagOnMain(String sessionId);
}

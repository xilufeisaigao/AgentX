package com.agentx.agentxbackend.delivery.application.port.out;

import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;

import java.util.Optional;

public interface DeliveryCloneRepositoryPort {

    DeliveryClonePublication publish(String sessionId);

    Optional<DeliveryClonePublication> findActive(String sessionId);

    int cleanupExpired();
}

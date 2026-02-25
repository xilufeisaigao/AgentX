package com.agentx.agentxbackend.delivery.application.port.in;

import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;

import java.util.Optional;

public interface DeliveryClonePublishUseCase {

    DeliveryClonePublication publish(String sessionId);

    Optional<DeliveryClonePublication> findActive(String sessionId);
}

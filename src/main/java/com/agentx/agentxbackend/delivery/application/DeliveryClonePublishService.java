package com.agentx.agentxbackend.delivery.application;

import com.agentx.agentxbackend.delivery.application.port.in.DeliveryCloneMaintenanceUseCase;
import com.agentx.agentxbackend.delivery.application.port.in.DeliveryClonePublishUseCase;
import com.agentx.agentxbackend.delivery.application.port.out.DeliveryCloneRepositoryPort;
import com.agentx.agentxbackend.delivery.domain.model.DeliveryClonePublication;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DeliveryClonePublishService implements DeliveryClonePublishUseCase, DeliveryCloneMaintenanceUseCase {

    private final DeliveryCloneRepositoryPort repositoryPort;

    public DeliveryClonePublishService(DeliveryCloneRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @Override
    public DeliveryClonePublication publish(String sessionId) {
        return repositoryPort.publish(sessionId);
    }

    @Override
    public Optional<DeliveryClonePublication> findActive(String sessionId) {
        return repositoryPort.findActive(sessionId);
    }

    @Override
    public int cleanupExpiredPublications() {
        return repositoryPort.cleanupExpired();
    }
}

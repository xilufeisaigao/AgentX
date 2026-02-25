package com.agentx.agentxbackend.delivery.infrastructure.external;

import com.agentx.agentxbackend.delivery.application.port.in.DeliveryCloneMaintenanceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DeliveryCloneCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCloneCleanupScheduler.class);

    private final DeliveryCloneMaintenanceUseCase maintenanceUseCase;
    private final boolean enabled;

    public DeliveryCloneCleanupScheduler(
        DeliveryCloneMaintenanceUseCase maintenanceUseCase,
        @Value("${agentx.delivery.clone-publish.cleanup.enabled:true}") boolean enabled
    ) {
        this.maintenanceUseCase = maintenanceUseCase;
        this.enabled = enabled;
    }

    @Scheduled(
        initialDelayString = "${agentx.delivery.clone-publish.cleanup.initial-delay-ms:120000}",
        fixedDelayString = "${agentx.delivery.clone-publish.cleanup.poll-interval-ms:600000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            int deleted = maintenanceUseCase.cleanupExpiredPublications();
            if (deleted > 0) {
                log.info("Delivery clone cleanup removed expired repos, deleted={}", deleted);
            }
        } catch (Exception ex) {
            log.error("Delivery clone cleanup poll failed", ex);
        }
    }
}

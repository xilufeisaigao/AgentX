package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.ArchitectTicketAutoProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ArchitectAutoProcessScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArchitectAutoProcessScheduler.class);

    private final ArchitectTicketAutoProcessorService autoProcessorService;
    private final boolean enabled;
    private final int maxTicketsPerPoll;

    public ArchitectAutoProcessScheduler(
        ArchitectTicketAutoProcessorService autoProcessorService,
        @Value("${agentx.architect.auto-processor.enabled:true}") boolean enabled,
        @Value("${agentx.architect.auto-processor.max-tickets-per-poll:8}") int maxTicketsPerPoll
    ) {
        this.autoProcessorService = autoProcessorService;
        this.enabled = enabled;
        this.maxTicketsPerPoll = Math.max(1, maxTicketsPerPoll);
    }

    @Scheduled(
        initialDelayString = "${agentx.architect.auto-processor.initial-delay-ms:2000}",
        fixedDelayString = "${agentx.architect.auto-processor.poll-interval-ms:2000}"
    )
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            ArchitectTicketAutoProcessorService.AutoProcessResult result = autoProcessorService.processOpenArchitectTickets(
                null,
                maxTicketsPerPoll
            );
            if (result.processedCount() > 0) {
                log.info(
                    "Architect auto-processor handled {} ticket(s): {}",
                    result.processedCount(),
                    result.processedTicketIds()
                );
            }
        } catch (Exception ex) {
            log.error("Architect auto-processor poll failed", ex);
        }
    }
}

package com.agentx.agentxbackend.execution.infrastructure.external;

import com.agentx.agentxbackend.execution.application.port.out.DomainEventPublisher;
import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class NoopRunDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoopRunDomainEventPublisher.class);
    private final ApplicationEventPublisher applicationEventPublisher;

    public NoopRunDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(RunNeedsDecisionEvent event) {
        log.info("Run NEED_DECISION published, runId={}, taskId={}", event.runId(), event.taskId());
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(RunNeedsClarificationEvent event) {
        log.info("Run NEED_CLARIFICATION published, runId={}, taskId={}", event.runId(), event.taskId());
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publish(RunFinishedEvent event) {
        log.info("Run FINISHED published, runId={}, taskId={}", event.runId(), event.taskId());
        applicationEventPublisher.publishEvent(event);
    }
}

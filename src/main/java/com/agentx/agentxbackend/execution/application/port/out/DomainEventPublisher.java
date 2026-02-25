package com.agentx.agentxbackend.execution.application.port.out;

import com.agentx.agentxbackend.execution.domain.event.RunFinishedEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsClarificationEvent;
import com.agentx.agentxbackend.execution.domain.event.RunNeedsDecisionEvent;

public interface DomainEventPublisher {

    void publish(RunNeedsDecisionEvent event);

    void publish(RunNeedsClarificationEvent event);

    void publish(RunFinishedEvent event);
}

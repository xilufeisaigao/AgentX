package com.agentx.platform.runtime.agentruntime;

import com.agentx.platform.domain.execution.model.AgentPoolInstance;

public interface AgentRuntime {

    AgentRuntimeHandle launch(ContainerLaunchSpec launchSpec);

    ContainerObservation observe(AgentPoolInstance agentInstance);

    void terminate(AgentPoolInstance agentInstance);

    EphemeralExecutionResult executeOnce(ContainerLaunchSpec launchSpec);

    EphemeralExecutionResult executeInRunningContainer(AgentPoolInstance agentInstance, ToolExecutionSpec executionSpec);
}

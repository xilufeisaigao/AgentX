package com.agentx.platform.runtime.agentkernel.model;

import com.agentx.platform.domain.catalog.model.AgentDefinition;

public interface ModelGateway {

    <T> StructuredModelResult<T> generateStructuredObject(
            AgentDefinition agentDefinition,
            String systemPrompt,
            String userPrompt,
            Class<T> responseType
    );
}

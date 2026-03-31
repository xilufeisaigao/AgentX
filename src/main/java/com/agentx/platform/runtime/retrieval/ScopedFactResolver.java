package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.ContextCompilationRequest;

import java.util.Map;

public interface ScopedFactResolver {

    Map<String, Object> resolveStructuredFacts(ContextCompilationRequest request);
}

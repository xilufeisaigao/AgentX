package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.FactBundle;
import org.springframework.stereotype.Component;

@Component
public class DefaultFactRetriever implements FactRetriever {

    private final ScopedFactResolver scopedFactResolver;

    public DefaultFactRetriever(ScopedFactResolver scopedFactResolver) {
        this.scopedFactResolver = scopedFactResolver;
    }

    @Override
    public FactBundle retrieve(ContextCompilationRequest request) {
        return new FactBundle(scopedFactResolver.resolveStructuredFacts(request));
    }
}

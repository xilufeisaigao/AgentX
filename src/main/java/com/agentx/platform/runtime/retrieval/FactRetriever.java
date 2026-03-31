package com.agentx.platform.runtime.retrieval;

import com.agentx.platform.runtime.context.ContextCompilationRequest;
import com.agentx.platform.runtime.context.FactBundle;

public interface FactRetriever {

    FactBundle retrieve(ContextCompilationRequest request);
}

package com.agentx.platform.runtime.context;

public interface ContextCompilationCenter {

    CompiledContextPack compile(ContextCompilationRequest request);
}

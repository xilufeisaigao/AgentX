package com.agentx.agentxbackend.contextpack.application.port.out;

public interface ArtifactStorePort {

    String store(String path, String content);
}

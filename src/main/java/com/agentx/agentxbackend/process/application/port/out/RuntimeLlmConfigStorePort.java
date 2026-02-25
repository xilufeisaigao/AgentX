package com.agentx.agentxbackend.process.application.port.out;

import java.util.Optional;

public interface RuntimeLlmConfigStorePort {

    Optional<StoredConfig> load();

    void save(StoredConfig config);

    record StoredConfig(
        String outputLanguage,
        StoredProfile requirementLlm,
        StoredProfile workerRuntimeLlm,
        long version,
        boolean customized
    ) {
    }

    record StoredProfile(
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        long timeoutMs
    ) {
    }
}

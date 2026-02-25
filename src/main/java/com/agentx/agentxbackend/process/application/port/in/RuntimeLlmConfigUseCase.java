package com.agentx.agentxbackend.process.application.port.in;

public interface RuntimeLlmConfigUseCase {

    RuntimeConfigView getCurrentConfig();

    RuntimeConfigView resolveForRequestLanguage(String requestedOutputLanguage);

    RuntimeConfigView apply(RuntimeConfigPatch patch);

    ConnectivityProbeResult probe(RuntimeConfigPatch patch);

    record RuntimeConfigPatch(
        String outputLanguage,
        LlmProfilePatch requirementLlm,
        LlmProfilePatch workerRuntimeLlm
    ) {
    }

    record LlmProfilePatch(
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        Long timeoutMs
    ) {
    }

    record RuntimeConfigView(
        String outputLanguage,
        LlmProfile requirementLlm,
        LlmProfile workerRuntimeLlm,
        long version,
        boolean customized
    ) {
    }

    record LlmProfile(
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        long timeoutMs
    ) {
    }

    record ConnectivityProbeResult(
        String outputLanguage,
        ProbeItem requirementLlm,
        ProbeItem workerRuntimeLlm,
        boolean allOk,
        long version,
        boolean customized
    ) {
    }

    record ProbeItem(
        boolean ok,
        String provider,
        String framework,
        String baseUrl,
        String model,
        long timeoutMs,
        long latencyMs,
        String responsePreview,
        String errorMessage
    ) {
    }
}

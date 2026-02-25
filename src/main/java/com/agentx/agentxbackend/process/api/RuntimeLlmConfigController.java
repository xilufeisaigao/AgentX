package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeLlmConfigController {

    private final RuntimeLlmConfigUseCase useCase;

    public RuntimeLlmConfigController(RuntimeLlmConfigUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/api/v0/runtime/llm-config")
    public ResponseEntity<RuntimeConfigResponse> getCurrent() {
        RuntimeLlmConfigUseCase.RuntimeConfigView config = useCase.getCurrentConfig();
        return ResponseEntity.ok(toRuntimeConfigResponse(config));
    }

    @PostMapping("/api/v0/runtime/llm-config:test")
    public ResponseEntity<?> test(@RequestBody(required = false) RuntimeConfigRequest request) {
        try {
            RuntimeLlmConfigUseCase.ConnectivityProbeResult result = useCase.probe(toPatch(request));
            return ResponseEntity.ok(toProbeResponse(result));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_CONFIG", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("PROBE_FAILED", ex.getMessage()));
        }
    }

    @PostMapping("/api/v0/runtime/llm-config:apply")
    public ResponseEntity<?> apply(@RequestBody RuntimeConfigRequest request) {
        try {
            RuntimeLlmConfigUseCase.RuntimeConfigView config = useCase.apply(toPatch(request));
            return ResponseEntity.ok(toRuntimeConfigResponse(config));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_CONFIG", ex.getMessage()));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("APPLY_FAILED", ex.getMessage()));
        }
    }

    private static RuntimeLlmConfigUseCase.RuntimeConfigPatch toPatch(RuntimeConfigRequest request) {
        if (request == null) {
            return new RuntimeLlmConfigUseCase.RuntimeConfigPatch(null, null, null);
        }
        return new RuntimeLlmConfigUseCase.RuntimeConfigPatch(
            request.outputLanguage(),
            toPatch(request.requirementLlm()),
            toPatch(request.workerRuntimeLlm())
        );
    }

    private static RuntimeLlmConfigUseCase.LlmProfilePatch toPatch(LlmConfigRequest request) {
        if (request == null) {
            return null;
        }
        return new RuntimeLlmConfigUseCase.LlmProfilePatch(
            request.provider(),
            request.framework(),
            request.baseUrl(),
            request.model(),
            request.apiKey(),
            request.timeoutMs()
        );
    }

    private static RuntimeConfigResponse toRuntimeConfigResponse(RuntimeLlmConfigUseCase.RuntimeConfigView config) {
        return new RuntimeConfigResponse(
            config.outputLanguage(),
            config.version(),
            config.customized(),
            toLlmConfigView(config.requirementLlm()),
            toLlmConfigView(config.workerRuntimeLlm())
        );
    }

    private static ProbeResponse toProbeResponse(RuntimeLlmConfigUseCase.ConnectivityProbeResult probeResult) {
        return new ProbeResponse(
            probeResult.outputLanguage(),
            probeResult.version(),
            probeResult.customized(),
            probeResult.allOk(),
            toProbeItem(probeResult.requirementLlm()),
            toProbeItem(probeResult.workerRuntimeLlm())
        );
    }

    private static ProbeItemResponse toProbeItem(RuntimeLlmConfigUseCase.ProbeItem item) {
        return new ProbeItemResponse(
            item.ok(),
            item.provider(),
            item.framework(),
            item.baseUrl(),
            item.model(),
            item.timeoutMs(),
            item.latencyMs(),
            item.responsePreview(),
            item.errorMessage()
        );
    }

    private static LlmConfigView toLlmConfigView(RuntimeLlmConfigUseCase.LlmProfile profile) {
        return new LlmConfigView(
            profile.provider(),
            profile.framework(),
            profile.baseUrl(),
            profile.model(),
            profile.timeoutMs(),
            profile.apiKey() != null && !profile.apiKey().isBlank(),
            maskApiKey(profile.apiKey())
        );
    }

    private static String maskApiKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    public record RuntimeConfigRequest(
        @JsonProperty("output_language") String outputLanguage,
        @JsonProperty("requirement_llm") LlmConfigRequest requirementLlm,
        @JsonProperty("worker_runtime_llm") LlmConfigRequest workerRuntimeLlm
    ) {
    }

    public record LlmConfigRequest(
        String provider,
        String framework,
        @JsonProperty("base_url") String baseUrl,
        String model,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("timeout_ms") Long timeoutMs
    ) {
    }

    public record RuntimeConfigResponse(
        @JsonProperty("output_language") String outputLanguage,
        long version,
        boolean customized,
        @JsonProperty("requirement_llm") LlmConfigView requirementLlm,
        @JsonProperty("worker_runtime_llm") LlmConfigView workerRuntimeLlm
    ) {
    }

    public record LlmConfigView(
        String provider,
        String framework,
        @JsonProperty("base_url") String baseUrl,
        String model,
        @JsonProperty("timeout_ms") long timeoutMs,
        @JsonProperty("api_key_configured") boolean apiKeyConfigured,
        @JsonProperty("api_key_masked") String apiKeyMasked
    ) {
    }

    public record ProbeResponse(
        @JsonProperty("output_language") String outputLanguage,
        long version,
        boolean customized,
        @JsonProperty("all_ok") boolean allOk,
        @JsonProperty("requirement_llm") ProbeItemResponse requirementLlm,
        @JsonProperty("worker_runtime_llm") ProbeItemResponse workerRuntimeLlm
    ) {
    }

    public record ProbeItemResponse(
        boolean ok,
        String provider,
        String framework,
        @JsonProperty("base_url") String baseUrl,
        String model,
        @JsonProperty("timeout_ms") long timeoutMs,
        @JsonProperty("latency_ms") long latencyMs,
        @JsonProperty("response_preview") String responsePreview,
        @JsonProperty("error_message") String errorMessage
    ) {
    }

    public record ErrorResponse(String code, String message) {
    }
}

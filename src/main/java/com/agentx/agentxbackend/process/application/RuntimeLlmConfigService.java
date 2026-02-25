package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import com.agentx.agentxbackend.process.application.port.out.LlmConnectivityTesterPort;
import com.agentx.agentxbackend.process.application.port.out.RuntimeLlmConfigStorePort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RuntimeLlmConfigService implements RuntimeLlmConfigUseCase {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLlmConfigService.class);
    private static final String DEFAULT_OUTPUT_LANGUAGE = "zh-CN";
    private static final String PROVIDER_MOCK = "mock";
    private static final String PROVIDER_BAILIAN = "bailian";
    private static final String FRAMEWORK_LANGCHAIN4J = "langchain4j";
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final LlmConnectivityTesterPort testerPort;
    private final RuntimeLlmConfigStorePort storePort;
    private final RuntimeConfigView defaultConfig;
    private final AtomicReference<RuntimeConfigView> overrideConfig = new AtomicReference<>();
    private final AtomicLong versionSequence = new AtomicLong(1);

    public RuntimeLlmConfigService(
        LlmConnectivityTesterPort testerPort,
        RuntimeLlmConfigStorePort storePort,
        @Value("${agentx.llm.output-language:zh-CN}") String outputLanguage,
        @Value("${agentx.requirement.llm.provider:mock}") String requirementProvider,
        @Value("${agentx.requirement.llm.framework:langchain4j}") String requirementFramework,
        @Value("${agentx.requirement.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String requirementBaseUrl,
        @Value("${agentx.requirement.llm.model:qwen3.5-plus-2026-02-15}") String requirementModel,
        @Value("${agentx.requirement.llm.api-key:}") String requirementApiKey,
        @Value("${agentx.requirement.llm.timeout-ms:120000}") long requirementTimeoutMs,
        @Value("${agentx.worker-runtime.llm.provider:mock}") String workerProvider,
        @Value("${agentx.worker-runtime.llm.framework:langchain4j}") String workerFramework,
        @Value("${agentx.worker-runtime.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String workerBaseUrl,
        @Value("${agentx.worker-runtime.llm.model:qwen3.5-plus-2026-02-15}") String workerModel,
        @Value("${agentx.worker-runtime.llm.api-key:}") String workerApiKey,
        @Value("${agentx.worker-runtime.command-timeout-ms:120000}") long workerTimeoutMs
    ) {
        this.testerPort = testerPort;
        this.storePort = storePort;
        this.defaultConfig = new RuntimeConfigView(
            normalizeOutputLanguage(outputLanguage),
            sanitizeProfile(
                new LlmProfile(
                    requirementProvider,
                    requirementFramework,
                    requirementBaseUrl,
                    requirementModel,
                    requirementApiKey,
                    requirementTimeoutMs
                )
            ),
            sanitizeProfile(
                new LlmProfile(
                    workerProvider,
                    workerFramework,
                    workerBaseUrl,
                    workerModel,
                    workerApiKey,
                    workerTimeoutMs
                )
            ),
            1L,
            false
        );
    }

    @PostConstruct
    void restoreFromStore() {
        try {
            storePort.load()
                .map(this::fromStoredConfig)
                .ifPresent(restored -> {
                    overrideConfig.set(restored);
                    versionSequence.set(Math.max(1L, restored.version()));
                });
        } catch (RuntimeException ex) {
            // keep service available even if persisted config is damaged
            log.warn("Failed to restore runtime llm config from store, fallback to defaults.", ex);
            overrideConfig.set(null);
            versionSequence.set(1L);
        }
    }

    @Override
    public RuntimeConfigView getCurrentConfig() {
        RuntimeConfigView config = overrideConfig.get();
        if (config != null) {
            return config;
        }
        return defaultConfig;
    }

    @Override
    public RuntimeConfigView resolveForRequestLanguage(String requestedOutputLanguage) {
        RuntimeConfigView current = getCurrentConfig();
        String outputLanguage = normalizeOutputLanguage(requestedOutputLanguage);
        if (outputLanguage.equals(current.outputLanguage())) {
            return current;
        }
        return new RuntimeConfigView(
            outputLanguage,
            current.requirementLlm(),
            current.workerRuntimeLlm(),
            current.version(),
            current.customized()
        );
    }

    @Override
    public RuntimeConfigView apply(RuntimeConfigPatch patch) {
        RuntimeConfigView current = getCurrentConfig();
        RuntimeConfigView merged = merge(current, patch, true);
        RuntimeConfigView next = new RuntimeConfigView(
            merged.outputLanguage(),
            merged.requirementLlm(),
            merged.workerRuntimeLlm(),
            versionSequence.incrementAndGet(),
            true
        );
        storePort.save(toStoredConfig(next));
        overrideConfig.set(next);
        return next;
    }

    @Override
    public ConnectivityProbeResult probe(RuntimeConfigPatch patch) {
        RuntimeConfigView current = getCurrentConfig();
        RuntimeConfigView candidate = merge(current, patch, false);

        ProbeItem requirementResult = toProbeItem(
            candidate.requirementLlm(),
            testerPort.test(new LlmConnectivityTesterPort.TestCommand(
                candidate.requirementLlm().provider(),
                candidate.requirementLlm().framework(),
                candidate.requirementLlm().baseUrl(),
                candidate.requirementLlm().model(),
                candidate.requirementLlm().apiKey(),
                candidate.outputLanguage(),
                candidate.requirementLlm().timeoutMs()
            ))
        );
        ProbeItem workerResult = toProbeItem(
            candidate.workerRuntimeLlm(),
            testerPort.test(new LlmConnectivityTesterPort.TestCommand(
                candidate.workerRuntimeLlm().provider(),
                candidate.workerRuntimeLlm().framework(),
                candidate.workerRuntimeLlm().baseUrl(),
                candidate.workerRuntimeLlm().model(),
                candidate.workerRuntimeLlm().apiKey(),
                candidate.outputLanguage(),
                candidate.workerRuntimeLlm().timeoutMs()
            ))
        );
        return new ConnectivityProbeResult(
            candidate.outputLanguage(),
            requirementResult,
            workerResult,
            requirementResult.ok() && workerResult.ok(),
            current.version(),
            current.customized()
        );
    }

    private RuntimeConfigView merge(RuntimeConfigView base, RuntimeConfigPatch patch, boolean strictValidation) {
        RuntimeConfigPatch safePatch = patch == null ? new RuntimeConfigPatch(null, null, null) : patch;
        String outputLanguage = normalizeOutputLanguage(
            firstNotBlank(safePatch.outputLanguage(), base.outputLanguage())
        );
        LlmProfile requirement = mergeProfile(base.requirementLlm(), safePatch.requirementLlm(), strictValidation);
        LlmProfile workerRuntime = mergeProfile(base.workerRuntimeLlm(), safePatch.workerRuntimeLlm(), strictValidation);
        return new RuntimeConfigView(outputLanguage, requirement, workerRuntime, base.version(), base.customized());
    }

    private LlmProfile mergeProfile(LlmProfile base, LlmProfilePatch patch, boolean strictValidation) {
        if (patch == null) {
            return sanitizeProfile(base);
        }
        LlmProfile merged = new LlmProfile(
            firstNotBlank(patch.provider(), base.provider()),
            firstNotBlank(patch.framework(), base.framework()),
            firstNotBlank(patch.baseUrl(), base.baseUrl()),
            firstNotBlank(patch.model(), base.model()),
            resolveApiKey(patch.apiKey(), base.apiKey()),
            patch.timeoutMs() == null ? base.timeoutMs() : patch.timeoutMs()
        );
        return strictValidation ? validateProfile(merged) : sanitizeProfile(merged);
    }

    private LlmProfile sanitizeProfile(LlmProfile profile) {
        if (profile == null) {
            return new LlmProfile(
                PROVIDER_MOCK,
                FRAMEWORK_LANGCHAIN4J,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.5-plus-2026-02-15",
                "",
                DEFAULT_TIMEOUT_MS
            );
        }
        String provider = normalizeProvider(profile.provider());
        String framework = normalizeFramework(profile.framework());
        String baseUrl = normalizeNullable(profile.baseUrl());
        String model = normalizeNullable(profile.model());
        String apiKey = normalizeNullable(profile.apiKey());
        long timeoutMs = normalizeTimeout(profile.timeoutMs());
        if (PROVIDER_MOCK.equals(provider)) {
            if (baseUrl == null) {
                baseUrl = profile.baseUrl();
            }
            if (model == null) {
                model = profile.model();
            }
            if (apiKey == null) {
                apiKey = "";
            }
            return new LlmProfile(provider, framework, nullSafe(baseUrl), nullSafe(model), nullSafe(apiKey), timeoutMs);
        }
        if (baseUrl == null) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (model == null) {
            model = "qwen3.5-plus-2026-02-15";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        return new LlmProfile(provider, framework, baseUrl, model, apiKey, timeoutMs);
    }

    private LlmProfile validateProfile(LlmProfile profile) {
        LlmProfile sanitized = sanitizeProfile(profile);
        if (PROVIDER_BAILIAN.equals(sanitized.provider())) {
            if (sanitized.baseUrl() == null || sanitized.baseUrl().isBlank()) {
                throw new IllegalArgumentException("base_url is required when provider=bailian");
            }
            if (sanitized.model() == null || sanitized.model().isBlank()) {
                throw new IllegalArgumentException("model is required when provider=bailian");
            }
            if (sanitized.apiKey() == null || sanitized.apiKey().isBlank()) {
                throw new IllegalArgumentException("api_key is required when provider=bailian");
            }
        }
        return sanitized;
    }

    private ProbeItem toProbeItem(LlmProfile config, LlmConnectivityTesterPort.TestResult result) {
        return new ProbeItem(
            result.ok(),
            config.provider(),
            config.framework(),
            config.baseUrl(),
            config.model(),
            config.timeoutMs(),
            result.latencyMs(),
            result.responsePreview(),
            result.errorMessage()
        );
    }

    private static String normalizeOutputLanguage(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            return DEFAULT_OUTPUT_LANGUAGE;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.equals("zh") || lower.equals("zh-cn") || lower.equals("cn") || lower.equals("chinese")) {
            return "zh-CN";
        }
        if (lower.equals("en") || lower.equals("en-us") || lower.equals("english")) {
            return "en-US";
        }
        if (lower.equals("ja") || lower.equals("ja-jp") || lower.equals("japanese")) {
            return "ja-JP";
        }
        if (!normalized.matches("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})?")) {
            return DEFAULT_OUTPUT_LANGUAGE;
        }
        return normalized.replace('_', '-');
    }

    private static String normalizeProvider(String value) {
        String normalized = normalizeToken(value);
        if (normalized == null || normalized.isBlank()) {
            return PROVIDER_MOCK;
        }
        if (PROVIDER_MOCK.equals(normalized) || PROVIDER_BAILIAN.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("unsupported provider: " + normalized);
    }

    private static String normalizeFramework(String value) {
        String normalized = normalizeToken(value);
        if (normalized == null || normalized.isBlank()) {
            return FRAMEWORK_LANGCHAIN4J;
        }
        if (FRAMEWORK_LANGCHAIN4J.equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("unsupported framework: " + normalized);
    }

    private static String normalizeToken(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static long normalizeTimeout(long timeoutMs) {
        return Math.max(1_000L, Math.min(300_000L, timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs));
    }

    private static String firstNotBlank(String preferred, String fallback) {
        String normalized = normalizeNullable(preferred);
        if (normalized != null) {
            return normalized;
        }
        return fallback;
    }

    private static String resolveApiKey(String preferred, String fallback) {
        if (preferred == null) {
            return fallback;
        }
        return preferred.trim();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private RuntimeLlmConfigStorePort.StoredConfig toStoredConfig(RuntimeConfigView view) {
        return new RuntimeLlmConfigStorePort.StoredConfig(
            view.outputLanguage(),
            toStoredProfile(view.requirementLlm()),
            toStoredProfile(view.workerRuntimeLlm()),
            view.version(),
            view.customized()
        );
    }

    private RuntimeLlmConfigStorePort.StoredProfile toStoredProfile(LlmProfile profile) {
        return new RuntimeLlmConfigStorePort.StoredProfile(
            profile.provider(),
            profile.framework(),
            profile.baseUrl(),
            profile.model(),
            profile.apiKey(),
            profile.timeoutMs()
        );
    }

    private RuntimeConfigView fromStoredConfig(RuntimeLlmConfigStorePort.StoredConfig stored) {
        LlmProfile requirement = sanitizeProfile(fromStoredProfile(stored.requirementLlm()));
        LlmProfile worker = sanitizeProfile(fromStoredProfile(stored.workerRuntimeLlm()));
        long version = Math.max(1L, stored.version());
        return new RuntimeConfigView(
            normalizeOutputLanguage(stored.outputLanguage()),
            requirement,
            worker,
            version,
            stored.customized()
        );
    }

    private LlmProfile fromStoredProfile(RuntimeLlmConfigStorePort.StoredProfile stored) {
        if (stored == null) {
            return sanitizeProfile(null);
        }
        return new LlmProfile(
            stored.provider(),
            stored.framework(),
            stored.baseUrl(),
            stored.model(),
            stored.apiKey(),
            stored.timeoutMs()
        );
    }
}

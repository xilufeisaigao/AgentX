package com.agentx.agentxbackend.process.application.port.out;

public interface LlmConnectivityTesterPort {

    TestResult test(TestCommand command);

    record TestCommand(
        String provider,
        String framework,
        String baseUrl,
        String model,
        String apiKey,
        String outputLanguage,
        long timeoutMs
    ) {
    }

    record TestResult(
        boolean ok,
        String provider,
        String framework,
        String baseUrl,
        String model,
        long latencyMs,
        String responsePreview,
        String errorMessage
    ) {
        public static TestResult success(
            String provider,
            String framework,
            String baseUrl,
            String model,
            long latencyMs,
            String responsePreview
        ) {
            return new TestResult(
                true,
                provider,
                framework,
                baseUrl,
                model,
                Math.max(0, latencyMs),
                responsePreview,
                null
            );
        }

        public static TestResult failed(
            String provider,
            String framework,
            String baseUrl,
            String model,
            String errorMessage
        ) {
            return new TestResult(
                false,
                provider,
                framework,
                baseUrl,
                model,
                0,
                null,
                errorMessage
            );
        }
    }
}

package com.agentx.platform.support.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RealWorkflowEvalScenarioPackLoader {

    private static final String CLASSPATH_ROOT = "evaluation/scenarios/";

    private RealWorkflowEvalScenarioPackLoader() {
    }

    public static LoadedScenarioPack load(ObjectMapper objectMapper, String scenarioReference) {
        try {
            ObjectMapper mapper = objectMapper.copy().findAndRegisterModules();
            Path path = Path.of(scenarioReference);
            if (Files.exists(path)) {
                String rawJson = Files.readString(path, StandardCharsets.UTF_8);
                return new LoadedScenarioPack(
                        mapper.readValue(rawJson, RealWorkflowEvalScenarioPack.class),
                        path.toAbsolutePath().normalize().toString(),
                        rawJson
                );
            }
            String normalizedName = scenarioReference.endsWith(".json")
                    ? scenarioReference
                    : scenarioReference + ".json";
            String resourcePath = CLASSPATH_ROOT + normalizedName;
            try (InputStream inputStream = RealWorkflowEvalScenarioPackLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (inputStream == null) {
                    throw new IllegalArgumentException("scenario pack not found: " + scenarioReference);
                }
                String rawJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                return new LoadedScenarioPack(
                        mapper.readValue(rawJson, RealWorkflowEvalScenarioPack.class),
                        "classpath:" + resourcePath,
                        rawJson
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load real workflow eval scenario pack: " + scenarioReference, exception);
        }
    }

    public record LoadedScenarioPack(
            RealWorkflowEvalScenarioPack pack,
            String sourceRef,
            String rawJson
    ) {
    }
}

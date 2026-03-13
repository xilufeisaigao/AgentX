package com.agentx.agentxbackend.process.infrastructure.external;

import com.agentx.agentxbackend.process.application.RuntimeLlmConfigService;
import com.agentx.agentxbackend.process.application.port.out.LlmConnectivityTesterPort;
import com.agentx.agentxbackend.process.application.port.out.RuntimeLlmConfigStorePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerRuntimeTimeoutConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(LlmConnectivityTesterPort.class, MockConnectivityTester::new)
        .withBean(RuntimeLlmConfigStorePort.class, InMemoryStore::new)
        .withBean(RuntimeLlmConfigService.class);

    @Test
    void shouldUseSeparateDefaultTimeoutsForLlmAndExecution() {
        contextRunner.run(context -> {
            RuntimeLlmConfigService configService = context.getBean(RuntimeLlmConfigService.class);
            LocalWorkerTaskExecutor executor = buildExecutorForTest(
                configService,
                context.getBean(ObjectMapper.class),
                context.getEnvironment().getProperty(
                    "agentx.worker-runtime.execution.command-timeout-ms",
                    Integer.class,
                    600_000
                )
            );

            assertEquals(120_000L, configService.getCurrentConfig().workerRuntimeLlm().timeoutMs());
            assertEquals(600_000, readExecutionTimeout(executor));
        });
    }

    @Test
    void shouldAllowOverridingLlmAndExecutionTimeoutsIndependently() {
        contextRunner
            .withPropertyValues(
                "agentx.worker-runtime.llm.timeout-ms=45000",
                "agentx.worker-runtime.execution.command-timeout-ms=240000"
            )
            .run(context -> {
                RuntimeLlmConfigService configService = context.getBean(RuntimeLlmConfigService.class);
                LocalWorkerTaskExecutor executor = buildExecutorForTest(
                    configService,
                    context.getBean(ObjectMapper.class),
                    context.getEnvironment().getProperty(
                        "agentx.worker-runtime.execution.command-timeout-ms",
                        Integer.class,
                        600_000
                    )
                );

                assertEquals(45_000L, configService.getCurrentConfig().workerRuntimeLlm().timeoutMs());
                assertEquals(240_000, readExecutionTimeout(executor));
            });
    }

    private static LocalWorkerTaskExecutor buildExecutorForTest(
        RuntimeLlmConfigService configService,
        ObjectMapper objectMapper,
        int executionTimeoutMs
    ) {
        return new LocalWorkerTaskExecutor(
            configService,
            objectMapper,
            "git",
            ".",
            "sessions",
            executionTimeoutMs,
            20,
            "mvn,./mvnw,gradle,./gradlew,python,pytest,git",
            false,
            "docker",
            "maven:3.9.11-eclipse-temurin-21",
            "1g",
            "1.0",
            256
        );
    }

    private static int readExecutionTimeout(LocalWorkerTaskExecutor executor) throws Exception {
        Field field = LocalWorkerTaskExecutor.class.getDeclaredField("commandTimeoutMs");
        field.setAccessible(true);
        return field.getInt(executor);
    }

    private static final class InMemoryStore implements RuntimeLlmConfigStorePort {
        @Override
        public Optional<StoredConfig> load() {
            return Optional.empty();
        }

        @Override
        public void save(StoredConfig config) {
        }
    }

    private static final class MockConnectivityTester implements LlmConnectivityTesterPort {
        @Override
        public TestResult test(TestCommand command) {
            return TestResult.success(
                command.provider(),
                command.framework(),
                command.baseUrl(),
                command.model(),
                1L,
                "ok"
            );
        }
    }
}

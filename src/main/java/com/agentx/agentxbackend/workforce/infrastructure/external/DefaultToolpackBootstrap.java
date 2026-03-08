package com.agentx.agentxbackend.workforce.infrastructure.external;

import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultToolpackBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolpackBootstrap.class);

    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final boolean bootstrapDefaultToolpacks;
    private final boolean bootstrapDefaultWorkers;

    public DefaultToolpackBootstrap(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        @Value("${agentx.workforce.bootstrap-default-toolpacks:true}") boolean bootstrapDefaultToolpacks,
        @Value("${agentx.workforce.bootstrap-default-workers:false}") boolean bootstrapDefaultWorkers
    ) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.bootstrapDefaultToolpacks = bootstrapDefaultToolpacks;
        this.bootstrapDefaultWorkers = bootstrapDefaultWorkers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!bootstrapDefaultToolpacks) {
            return;
        }
        register(
            "TP-JAVA-17",
            "java",
            "17",
            "language",
            "Java runtime compatibility target for legacy Spring Boot task plans."
        );
        register(
            "TP-JAVA-21",
            "java",
            "21",
            "language",
            "Java runtime for backend implementation and tests."
        );
        register(
            "TP-MAVEN-3",
            "maven",
            "3.x",
            "build",
            "Maven build lifecycle and dependency management."
        );
        register(
            "TP-GIT-2",
            "git",
            "2.x",
            "script",
            "Git CLI for branch/worktree operations."
        );
        register(
            "TP-MYSQL-8",
            "mysql",
            "8.x",
            "misc",
            "MySQL client capability for local verification."
        );
        register(
            "TP-PYTHON-3_11",
            "python",
            "3.11",
            "language",
            "Python runtime for API automation scripts."
        );

        if (bootstrapDefaultWorkers) {
            registerDefaultWorkers();
        }
    }

    private void register(String id, String name, String version, String kind, String description) {
        try {
            workerCapabilityUseCase.registerToolpack(id, name, version, kind, description);
        } catch (RuntimeException ex) {
            log.warn("Skip default toolpack bootstrap: id={}, reason={}", id, ex.getMessage());
        }
    }

    private void registerDefaultWorkers() {
        registerWorkerProfile(
            "WRK-BOOT-JAVA-CORE",
            List.of("TP-GIT-2", "TP-JAVA-17", "TP-JAVA-21", "TP-MAVEN-3")
        );
        registerWorkerProfile(
            "WRK-BOOT-JAVA-DB",
            List.of("TP-GIT-2", "TP-JAVA-17", "TP-JAVA-21", "TP-MAVEN-3", "TP-MYSQL-8")
        );
        registerWorkerProfile(
            "WRK-BOOT-PYTHON-AUX",
            List.of("TP-GIT-2", "TP-PYTHON-3_11")
        );
    }

    private void registerWorkerProfile(String workerId, List<String> toolpackIds) {
        try {
            boolean exists = workerCapabilityUseCase.workerExists(workerId);
            if (!exists) {
                workerCapabilityUseCase.registerWorker(workerId);
            } else {
                workerCapabilityUseCase.updateWorkerStatus(workerId, WorkerStatus.READY);
            }
            workerCapabilityUseCase.bindToolpacks(workerId, toolpackIds);
            if (!exists) {
                workerCapabilityUseCase.updateWorkerStatus(workerId, WorkerStatus.READY);
            }
        } catch (RuntimeException ex) {
            log.warn(
                "Skip default worker bootstrap: workerId={}, toolpacks={}, reason={}",
                workerId,
                toolpackIds,
                ex.getMessage()
            );
        }
    }
}

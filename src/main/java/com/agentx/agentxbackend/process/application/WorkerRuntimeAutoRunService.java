package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.PreconditionFailedException;
import com.agentx.agentxbackend.execution.application.port.in.RunCommandUseCase;
import com.agentx.agentxbackend.execution.application.port.in.RunInternalUseCase;
import com.agentx.agentxbackend.execution.domain.model.RunFinishedPayload;
import com.agentx.agentxbackend.execution.domain.model.TaskPackage;
import com.agentx.agentxbackend.process.application.port.out.WorkerTaskExecutorPort;
import com.agentx.agentxbackend.workforce.application.port.in.WorkerCapabilityUseCase;
import com.agentx.agentxbackend.workforce.domain.model.Worker;
import com.agentx.agentxbackend.workforce.domain.model.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class WorkerRuntimeAutoRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntimeAutoRunService.class);

    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final RunCommandUseCase runCommandUseCase;
    private final RunInternalUseCase runInternalUseCase;
    private final WorkerTaskExecutorPort workerTaskExecutorPort;
    private final ScheduledExecutorService heartbeatExecutor;
    private final int heartbeatIntervalSeconds;
    private final ReentrantLock runOnceLock = new ReentrantLock();

    @Autowired
    public WorkerRuntimeAutoRunService(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        RunCommandUseCase runCommandUseCase,
        RunInternalUseCase runInternalUseCase,
        WorkerTaskExecutorPort workerTaskExecutorPort,
        @Value("${agentx.worker-runtime.heartbeat-interval-seconds:60}") int heartbeatIntervalSeconds
    ) {
        this(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort,
            heartbeatIntervalSeconds,
            createHeartbeatExecutor()
        );
    }

    WorkerRuntimeAutoRunService(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        RunCommandUseCase runCommandUseCase,
        RunInternalUseCase runInternalUseCase,
        WorkerTaskExecutorPort workerTaskExecutorPort
    ) {
        this(
            workerCapabilityUseCase,
            runCommandUseCase,
            runInternalUseCase,
            workerTaskExecutorPort,
            60,
            createHeartbeatExecutor()
        );
    }

    WorkerRuntimeAutoRunService(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        RunCommandUseCase runCommandUseCase,
        RunInternalUseCase runInternalUseCase,
        WorkerTaskExecutorPort workerTaskExecutorPort,
        int heartbeatIntervalSeconds,
        ScheduledExecutorService heartbeatExecutor
    ) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.runCommandUseCase = runCommandUseCase;
        this.runInternalUseCase = runInternalUseCase;
        this.workerTaskExecutorPort = workerTaskExecutorPort;
        this.heartbeatIntervalSeconds = Math.max(0, heartbeatIntervalSeconds);
        this.heartbeatExecutor = heartbeatExecutor;
    }

    public AutoRunResult runOnce(int maxWorkers) {
        if (!runOnceLock.tryLock()) {
            log.debug("Skip worker runtime auto-run because another runOnce is still executing.");
            return new AutoRunResult(0, 0, 0, 0, 0);
        }
        try {
            int cappedMaxWorkers = maxWorkers <= 0 ? 8 : Math.min(maxWorkers, 256);
            List<Worker> readyWorkers = workerCapabilityUseCase.listWorkersByStatus(
                WorkerStatus.READY,
                cappedMaxWorkers
            );

            int claimedRuns = 0;
            int succeededRuns = 0;
            int needInputRuns = 0;
            int failedRuns = 0;

            for (Worker worker : readyWorkers) {
                Optional<TaskPackage> claimed = safePickupRunningVerify(worker.workerId());
                try {
                    if (claimed.isEmpty()) {
                        claimed = runCommandUseCase.claimTask(worker.workerId());
                    }
                } catch (PreconditionFailedException ex) {
                    // Claim precondition failures (e.g. INIT gate) are expected guardrails.
                    log.debug(
                        "Skip worker claim due to precondition guard, workerId={}, reason={}",
                        worker.workerId(),
                        ex.getMessage()
                    );
                } catch (RuntimeException ex) {
                    failedRuns++;
                    log.warn(
                        "Worker claim failed, workerId={}, reason={}",
                        worker.workerId(),
                        ex.getMessage()
                    );
                    continue;
                }
                if (claimed.isEmpty()) {
                    continue;
                }
                claimedRuns++;
                TaskPackage taskPackage = claimed.get();
                try {
                    WorkerTaskExecutorPort.ExecutionResult executionResult;
                    try (RunHeartbeat ignored = startHeartbeat(taskPackage.runId())) {
                        executionResult = workerTaskExecutorPort.execute(taskPackage);
                    }
                    if (executionResult.status() == WorkerTaskExecutorPort.ExecutionStatus.SUCCEEDED) {
                        runCommandUseCase.finishRun(
                            taskPackage.runId(),
                            new RunFinishedPayload(
                                "SUCCEEDED",
                                defaultText(executionResult.workReport(), "Worker execution finished."),
                                executionResult.deliveryCommit(),
                                executionResult.artifactRefsJson()
                            )
                        );
                        succeededRuns++;
                        continue;
                    }
                    if (executionResult.status() == WorkerTaskExecutorPort.ExecutionStatus.NEED_INPUT) {
                        runCommandUseCase.appendEvent(
                            taskPackage.runId(),
                            defaultText(executionResult.needEventType(), "NEED_CLARIFICATION"),
                            defaultText(executionResult.needBody(), "Worker needs user input to continue."),
                            executionResult.needDataJson()
                        );
                        needInputRuns++;
                        continue;
                    }
                    runInternalUseCase.failRun(
                        taskPackage.runId(),
                        defaultText(executionResult.failureReason(), "Worker execution failed.")
                    );
                    failedRuns++;
                } catch (RuntimeException ex) {
                    try {
                        runInternalUseCase.failRun(taskPackage.runId(), "Worker runtime exception: " + ex.getMessage());
                    } catch (RuntimeException ignored) {
                        // best effort: run may already be terminal.
                    }
                    failedRuns++;
                }
            }
            return new AutoRunResult(
                readyWorkers.size(),
                claimedRuns,
                succeededRuns,
                needInputRuns,
                failedRuns
            );
        } finally {
            runOnceLock.unlock();
        }
    }

    private RunHeartbeat startHeartbeat(String runId) {
        if (heartbeatIntervalSeconds <= 0) {
            return RunHeartbeat.noop();
        }
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(
            () -> {
                try {
                    runCommandUseCase.heartbeat(runId);
                } catch (RuntimeException ex) {
                    log.debug("Run heartbeat skipped, runId={}, reason={}", runId, ex.getMessage());
                }
            },
            heartbeatIntervalSeconds,
            heartbeatIntervalSeconds,
            TimeUnit.SECONDS
        );
        return new RunHeartbeat(future);
    }

    private static ScheduledExecutorService createHeartbeatExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "agentx-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private Optional<TaskPackage> safePickupRunningVerify(String workerId) {
        try {
            return runCommandUseCase.pickupRunningVerifyRun(workerId);
        } catch (RuntimeException ex) {
            log.warn(
                "Pickup running verify run failed, workerId={}, reason={}",
                workerId,
                ex.getMessage()
            );
            return Optional.empty();
        }
    }

    private static String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    public record AutoRunResult(
        int scannedReadyWorkers,
        int claimedRuns,
        int succeededRuns,
        int needInputRuns,
        int failedRuns
    ) {
    }

    private record RunHeartbeat(ScheduledFuture<?> future) implements AutoCloseable {

        private static RunHeartbeat noop() {
            return new RunHeartbeat(null);
        }

        @Override
        public void close() {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}

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
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WorkerRuntimeAutoRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkerRuntimeAutoRunService.class);

    private final WorkerCapabilityUseCase workerCapabilityUseCase;
    private final RunCommandUseCase runCommandUseCase;
    private final RunInternalUseCase runInternalUseCase;
    private final WorkerTaskExecutorPort workerTaskExecutorPort;

    public WorkerRuntimeAutoRunService(
        WorkerCapabilityUseCase workerCapabilityUseCase,
        RunCommandUseCase runCommandUseCase,
        RunInternalUseCase runInternalUseCase,
        WorkerTaskExecutorPort workerTaskExecutorPort
    ) {
        this.workerCapabilityUseCase = workerCapabilityUseCase;
        this.runCommandUseCase = runCommandUseCase;
        this.runInternalUseCase = runInternalUseCase;
        this.workerTaskExecutorPort = workerTaskExecutorPort;
    }

    public AutoRunResult runOnce(int maxWorkers) {
        int cappedMaxWorkers = maxWorkers <= 0 ? 8 : Math.min(maxWorkers, 256);
        List<Worker> readyWorkers = workerCapabilityUseCase.listWorkersByStatus(WorkerStatus.READY, cappedMaxWorkers);

        int claimedRuns = 0;
        int succeededRuns = 0;
        int needInputRuns = 0;
        int failedRuns = 0;

        for (Worker worker : readyWorkers) {
            Optional<TaskPackage> claimed = Optional.empty();
            try {
                claimed = runCommandUseCase.claimTask(worker.workerId());
            } catch (PreconditionFailedException ex) {
                // Claim precondition failures (e.g. INIT gate) are expected guardrails.
                // Still attempt to pick RUNNING VERIFY runs created by merge-gate for this worker.
                log.debug(
                    "Skip worker claim due to precondition guard, workerId={}, reason={}",
                    worker.workerId(),
                    ex.getMessage()
                );
                claimed = safePickupRunningVerify(worker.workerId());
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
                claimed = safePickupRunningVerify(worker.workerId());
            }
            if (claimed.isEmpty()) {
                continue;
            }
            claimedRuns++;
            TaskPackage taskPackage = claimed.get();
            try {
                WorkerTaskExecutorPort.ExecutionResult executionResult = workerTaskExecutorPort.execute(taskPackage);
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
}

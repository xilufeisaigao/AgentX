package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.execution.application.port.in.RunQueryUseCase;
import com.agentx.agentxbackend.execution.domain.model.RunKind;
import com.agentx.agentxbackend.execution.domain.model.RunStatus;
import com.agentx.agentxbackend.execution.domain.model.TaskRun;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateMaintenanceUseCase;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.agentx.agentxbackend.planning.application.port.in.TaskQueryUseCase;
import com.agentx.agentxbackend.planning.application.port.in.TaskStateMutationUseCase;
import com.agentx.agentxbackend.planning.domain.model.TaskStatus;
import com.agentx.agentxbackend.planning.domain.model.TaskTemplateId;
import com.agentx.agentxbackend.planning.domain.model.WorkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class RuntimeGarbageCollectionService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeGarbageCollectionService.class);

    private final TaskQueryUseCase taskQueryUseCase;
    private final TaskStateMutationUseCase taskStateMutationUseCase;
    private final RunQueryUseCase runQueryUseCase;
    private final MergeGateUseCase mergeGateUseCase;
    private final MergeGateMaintenanceUseCase mergeGateMaintenanceUseCase;
    private final DeliveredTaskMergeGateProcessManager deliveredTaskMergeGateProcessManager;
    private final int assignedScanLimit;
    private final int deliveredScanLimit;
    private final long deliveredStaleSeconds;
    private final int maxMergeGateStartsPerPoll;

    public RuntimeGarbageCollectionService(
        TaskQueryUseCase taskQueryUseCase,
        TaskStateMutationUseCase taskStateMutationUseCase,
        RunQueryUseCase runQueryUseCase,
        MergeGateUseCase mergeGateUseCase,
        MergeGateMaintenanceUseCase mergeGateMaintenanceUseCase,
        DeliveredTaskMergeGateProcessManager deliveredTaskMergeGateProcessManager,
        @Value("${agentx.process.runtime-garbage-collector.assigned-scan-limit:128}") int assignedScanLimit,
        @Value("${agentx.process.runtime-garbage-collector.delivered-scan-limit:128}") int deliveredScanLimit,
        @Value("${agentx.process.runtime-garbage-collector.delivered-stale-seconds:120}") long deliveredStaleSeconds,
        @Value("${agentx.process.runtime-garbage-collector.max-mergegate-starts-per-poll:4}") int maxMergeGateStartsPerPoll
    ) {
        this.taskQueryUseCase = taskQueryUseCase;
        this.taskStateMutationUseCase = taskStateMutationUseCase;
        this.runQueryUseCase = runQueryUseCase;
        this.mergeGateUseCase = mergeGateUseCase;
        this.mergeGateMaintenanceUseCase = mergeGateMaintenanceUseCase;
        this.deliveredTaskMergeGateProcessManager = deliveredTaskMergeGateProcessManager;
        this.assignedScanLimit = clamp(assignedScanLimit, 1, 1000);
        this.deliveredScanLimit = clamp(deliveredScanLimit, 1, 1000);
        this.deliveredStaleSeconds = Math.max(30L, deliveredStaleSeconds);
        this.maxMergeGateStartsPerPoll = clamp(maxMergeGateStartsPerPoll, 1, 100);
    }

    public CleanupResult collectOnce() {
        boolean repositoryRecovered = false;
        int repositoryRecoveryFailures = 0;
        try {
            repositoryRecovered = mergeGateMaintenanceUseCase.recoverRepositoryIfNeeded();
        } catch (RuntimeException ex) {
            repositoryRecoveryFailures = 1;
            log.warn("Repository maintenance recovery failed: {}", ex.getMessage());
        }

        List<WorkTask> assignedTasks = taskQueryUseCase.listTasksByStatus(TaskStatus.ASSIGNED, assignedScanLimit);
        int releasedAssignments = 0;
        int promotedDeliveredAssignments = 0;
        int completedVerifyAssignments = 0;
        int skippedHealthyAssigned = 0;
        int failedAssignmentReleases = 0;
        for (WorkTask task : assignedTasks) {
            AssignedTaskDisposition disposition;
            try {
                disposition = inspectAssignedTask(task);
            } catch (RuntimeException ex) {
                failedAssignmentReleases++;
                log.warn(
                    "Failed to inspect ASSIGNED task binding, taskId={}, activeRunId={}, reason={}",
                    task.taskId(),
                    task.activeRunId(),
                    ex.getMessage()
                );
                continue;
            }
            if (disposition == AssignedTaskDisposition.KEEP) {
                skippedHealthyAssigned++;
                continue;
            }
            if (disposition == AssignedTaskDisposition.PROMOTE_DELIVERED) {
                try {
                    taskStateMutationUseCase.markDelivered(task.taskId());
                    deliveredTaskMergeGateProcessManager.onTaskDelivered(task.taskId());
                    promotedDeliveredAssignments++;
                } catch (RuntimeException ex) {
                    failedAssignmentReleases++;
                    log.warn(
                        "Failed to promote completed IMPL assignment into DELIVERED, taskId={}, activeRunId={}, reason={}",
                        task.taskId(),
                        task.activeRunId(),
                        ex.getMessage()
                    );
                }
                continue;
            }
            if (disposition == AssignedTaskDisposition.COMPLETE_VERIFY) {
                try {
                    taskStateMutationUseCase.markDone(task.taskId());
                    completedVerifyAssignments++;
                } catch (RuntimeException ex) {
                    failedAssignmentReleases++;
                    log.warn(
                        "Failed to complete succeeded VERIFY assignment, taskId={}, activeRunId={}, reason={}",
                        task.taskId(),
                        task.activeRunId(),
                        ex.getMessage()
                    );
                }
                continue;
            }
            try {
                taskStateMutationUseCase.releaseAssignment(task.taskId());
                releasedAssignments++;
            } catch (RuntimeException ex) {
                failedAssignmentReleases++;
                log.warn(
                    "Failed to release stale task assignment, taskId={}, activeRunId={}, reason={}",
                    task.taskId(),
                    task.activeRunId(),
                    ex.getMessage()
                );
            }
        }

        List<WorkTask> deliveredTasks = taskQueryUseCase.listTasksByStatus(TaskStatus.DELIVERED, deliveredScanLimit);
        Instant staleBefore = Instant.now().minusSeconds(deliveredStaleSeconds);
        int staleDelivered = 0;
        int kickedMergeGates = 0;
        int skippedDeliveredWithActiveVerify = 0;
        int skippedDeliveredWithVerifyHistory = 0;
        int skippedDeliveredByKickLimit = 0;
        int mergeGateRejected = 0;
        int mergeGateFailures = 0;

        for (WorkTask task : deliveredTasks) {
            if (!isDeliveredStale(task, staleBefore)) {
                continue;
            }
            staleDelivered++;
            try {
                if (runQueryUseCase.hasActiveRunByTaskAndKind(task.taskId(), RunKind.VERIFY)) {
                    skippedDeliveredWithActiveVerify++;
                    continue;
                }

                runQueryUseCase.findLatestRunByTaskAndKind(task.taskId(), RunKind.VERIFY);

                if (kickedMergeGates >= maxMergeGateStartsPerPoll) {
                    skippedDeliveredByKickLimit++;
                    continue;
                }

                MergeGateResult result = mergeGateUseCase.start(task.taskId());
                if (result.accepted()) {
                    kickedMergeGates++;
                } else {
                    mergeGateRejected++;
                }
            } catch (RuntimeException ex) {
                mergeGateFailures++;
                deliveredTaskMergeGateProcessManager.handleMergeGateStartFailure(task.taskId(), ex);
                log.warn(
                    "Failed to process delivered task in runtime garbage collector, taskId={}, reason={}",
                    task.taskId(),
                    ex.getMessage()
                );
            }
        }

        return new CleanupResult(
            assignedTasks.size(),
            releasedAssignments,
            promotedDeliveredAssignments,
            completedVerifyAssignments,
            skippedHealthyAssigned,
            failedAssignmentReleases,
            deliveredTasks.size(),
            staleDelivered,
            kickedMergeGates,
            skippedDeliveredWithActiveVerify,
            skippedDeliveredWithVerifyHistory,
            skippedDeliveredByKickLimit,
            mergeGateRejected,
            mergeGateFailures,
            repositoryRecovered,
            repositoryRecoveryFailures
        );
    }

    private AssignedTaskDisposition inspectAssignedTask(WorkTask task) {
        if (task == null) {
            return AssignedTaskDisposition.KEEP;
        }
        String activeRunId = normalize(task.activeRunId());
        if (activeRunId == null) {
            return AssignedTaskDisposition.RELEASE;
        }
        Optional<TaskRun> runOptional = runQueryUseCase.findRunById(activeRunId);
        if (runOptional.isEmpty()) {
            return AssignedTaskDisposition.RELEASE;
        }
        TaskRun run = runOptional.get();
        if (!task.taskId().equals(run.taskId())) {
            return AssignedTaskDisposition.RELEASE;
        }
        if (run.runKind() == RunKind.IMPL && run.status() == RunStatus.SUCCEEDED) {
            return AssignedTaskDisposition.PROMOTE_DELIVERED;
        }
        if (run.runKind() == RunKind.VERIFY
            && run.status() == RunStatus.SUCCEEDED
            && task.taskTemplateId() == TaskTemplateId.TMPL_VERIFY_V0) {
            return AssignedTaskDisposition.COMPLETE_VERIFY;
        }
        return isRunActive(run.status()) ? AssignedTaskDisposition.KEEP : AssignedTaskDisposition.RELEASE;
    }

    private static boolean isRunActive(RunStatus status) {
        return status == RunStatus.RUNNING || status == RunStatus.WAITING_FOREMAN;
    }

    private static boolean isDeliveredStale(WorkTask task, Instant staleBefore) {
        if (task == null) {
            return false;
        }
        Instant updatedAt = task.updatedAt();
        if (updatedAt == null) {
            return true;
        }
        return !updatedAt.isAfter(staleBefore);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    public record CleanupResult(
        int scannedAssignedTasks,
        int releasedAssignments,
        int promotedDeliveredAssignments,
        int completedVerifyAssignments,
        int skippedHealthyAssignedTasks,
        int failedAssignmentReleases,
        int scannedDeliveredTasks,
        int staleDeliveredTasks,
        int kickedMergeGates,
        int skippedDeliveredWithActiveVerify,
        int skippedDeliveredWithVerifyHistory,
        int skippedDeliveredByKickLimit,
        int mergeGateRejected,
        int mergeGateFailures,
        boolean repositoryRecovered,
        int repositoryRecoveryFailures
    ) {
    }

    private enum AssignedTaskDisposition {
        KEEP,
        RELEASE,
        PROMOTE_DELIVERED,
        COMPLETE_VERIFY
    }
}

package com.agentx.agentxbackend.process.application;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class DeliveredTaskMergeGateProcessManager {

    private static final Logger log = LoggerFactory.getLogger(DeliveredTaskMergeGateProcessManager.class);

    private final MergeGateUseCase mergeGateUseCase;
    private final MergeConflictRecoveryProcessManager mergeConflictRecoveryProcessManager;

    public DeliveredTaskMergeGateProcessManager(
        MergeGateUseCase mergeGateUseCase,
        MergeConflictRecoveryProcessManager mergeConflictRecoveryProcessManager
    ) {
        this.mergeGateUseCase = mergeGateUseCase;
        this.mergeConflictRecoveryProcessManager = mergeConflictRecoveryProcessManager;
    }

    public void onTaskDelivered(String taskId) {
        String normalizedTaskId = normalize(taskId);
        if (normalizedTaskId == null) {
            return;
        }
        try {
            MergeGateResult result = mergeGateUseCase.start(normalizedTaskId);
            if (!result.accepted()) {
                log.debug(
                    "Merge gate not accepted for delivered task yet, taskId={}, reason={}",
                    normalizedTaskId,
                    result.message()
                );
            }
        } catch (RuntimeException ex) {
            handleMergeGateStartFailure(normalizedTaskId, ex);
        }
    }

    public void handleMergeGateStartFailure(String taskId, RuntimeException ex) {
        String normalizedTaskId = normalize(taskId);
        if (normalizedTaskId == null || ex == null) {
            return;
        }
        String message = ex.getMessage();
        if (isMergeConflict(message)) {
            mergeConflictRecoveryProcessManager.onMergeConflict(normalizedTaskId, message);
            return;
        }
        log.warn(
            "Merge gate start failed, taskId={}, reason={}",
            normalizedTaskId,
            message
        );
    }

    private static boolean isMergeConflict(String message) {
        String normalized = normalize(message);
        if (normalized == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.contains("git rebase failed")
            || lower.contains("merge conflict")
            || lower.contains("conflict")
            || lower.contains("needs merge");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

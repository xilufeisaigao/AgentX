package com.agentx.agentxbackend.process.application.port.out;

import com.agentx.agentxbackend.execution.domain.model.TaskPackage;

public interface WorkerTaskExecutorPort {

    ExecutionResult execute(TaskPackage taskPackage);

    record ExecutionResult(
        ExecutionStatus status,
        String workReport,
        String deliveryCommit,
        String artifactRefsJson,
        String needEventType,
        String needBody,
        String needDataJson,
        String failureReason
    ) {
        public static ExecutionResult succeeded(String workReport, String deliveryCommit, String artifactRefsJson) {
            return new ExecutionResult(
                ExecutionStatus.SUCCEEDED,
                workReport,
                deliveryCommit,
                artifactRefsJson,
                null,
                null,
                null,
                null
            );
        }

        public static ExecutionResult needInput(String eventType, String body, String dataJson) {
            return new ExecutionResult(
                ExecutionStatus.NEED_INPUT,
                null,
                null,
                null,
                eventType,
                body,
                dataJson,
                null
            );
        }

        public static ExecutionResult failed(String reason) {
            return new ExecutionResult(
                ExecutionStatus.FAILED,
                null,
                null,
                null,
                null,
                null,
                null,
                reason
            );
        }
    }

    enum ExecutionStatus {
        SUCCEEDED,
        NEED_INPUT,
        FAILED
    }
}


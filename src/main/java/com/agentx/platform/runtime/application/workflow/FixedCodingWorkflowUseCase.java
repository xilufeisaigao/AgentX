package com.agentx.platform.runtime.application.workflow;

public interface FixedCodingWorkflowUseCase {

    String start(StartCodingWorkflowCommand command);

    WorkflowRuntimeSnapshot runUntilStable(String workflowRunId);

    WorkflowRuntimeSnapshot answerTicket(AnswerTicketCommand command);

    WorkflowRuntimeSnapshot getRuntimeSnapshot(String workflowRunId);
}

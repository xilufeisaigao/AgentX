package com.agentx.platform.runtime.application.workflow;

public interface FixedCodingWorkflowUseCase {

    String start(StartCodingWorkflowCommand command);

    WorkflowRuntimeSnapshot runUntilStable(String workflowRunId);

    WorkflowRuntimeSnapshot answerTicket(AnswerTicketCommand command);

    WorkflowRuntimeSnapshot confirmRequirementDoc(ConfirmRequirementDocCommand command);

    WorkflowRuntimeSnapshot editRequirementDoc(EditRequirementDocCommand command);

    WorkflowRuntimeSnapshot getRuntimeSnapshot(String workflowRunId);
}

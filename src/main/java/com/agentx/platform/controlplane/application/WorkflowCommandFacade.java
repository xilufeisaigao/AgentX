package com.agentx.platform.controlplane.application;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.shared.model.ActorRef;
import com.agentx.platform.domain.shared.model.ActorType;
import com.agentx.platform.runtime.application.workflow.AnswerTicketCommand;
import com.agentx.platform.runtime.application.workflow.ConfirmRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.EditRequirementDocCommand;
import com.agentx.platform.runtime.application.workflow.FixedCodingWorkflowUseCase;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import com.agentx.platform.runtime.application.workflow.WorkflowRuntimeSnapshot;
import com.agentx.platform.runtime.application.workflow.WorkflowScenario;
import org.springframework.stereotype.Service;

@Service
public class WorkflowCommandFacade {

    private final FixedCodingWorkflowUseCase workflowUseCase;

    public WorkflowCommandFacade(FixedCodingWorkflowUseCase workflowUseCase) {
        this.workflowUseCase = workflowUseCase;
    }

    public WorkflowCommandResult startWorkflow(
            String title,
            String requirementTitle,
            String requirementContent,
            String profileId,
            String createdByActorId,
            boolean autoAgentMode
    ) {
        String workflowRunId = workflowUseCase.start(new StartCodingWorkflowCommand(
                title,
                requirementTitle,
                requirementContent,
                profileId,
                humanActor(createdByActorId),
                autoAgentMode,
                WorkflowScenario.defaultScenario()
        ));
        return WorkflowCommandResult.fromSnapshot(workflowUseCase.getRuntimeSnapshot(workflowRunId));
    }

    public WorkflowCommandResult driveWorkflow(String workflowRunId) {
        return WorkflowCommandResult.fromSnapshot(workflowUseCase.runUntilStable(workflowRunId));
    }

    public WorkflowCommandResult answerTicket(String ticketId, String answer, String answeredByActorId) {
        return WorkflowCommandResult.fromSnapshot(workflowUseCase.answerTicket(new AnswerTicketCommand(
                ticketId,
                answer,
                humanActor(answeredByActorId)
        )));
    }

    public WorkflowCommandResult editRequirement(
            String workflowRunId,
            String docId,
            String title,
            String content,
            String editedByActorId
    ) {
        RequirementDoc requirementDoc = currentRequirementDoc(workflowRunId);
        if (!requirementDoc.docId().equals(docId)) {
            throw new IllegalArgumentException("requirement doc %s does not belong to workflow %s".formatted(docId, workflowRunId));
        }
        return WorkflowCommandResult.fromSnapshot(workflowUseCase.editRequirementDoc(new EditRequirementDocCommand(
                docId,
                title,
                content,
                humanActor(editedByActorId)
        )));
    }

    public WorkflowCommandResult confirmRequirement(
            String workflowRunId,
            String docId,
            int version,
            String confirmedByActorId
    ) {
        RequirementDoc requirementDoc = currentRequirementDoc(workflowRunId);
        if (!requirementDoc.docId().equals(docId)) {
            throw new IllegalArgumentException("requirement doc %s does not belong to workflow %s".formatted(docId, workflowRunId));
        }
        return WorkflowCommandResult.fromSnapshot(workflowUseCase.confirmRequirementDoc(new ConfirmRequirementDocCommand(
                docId,
                version,
                humanActor(confirmedByActorId)
        )));
    }

    private RequirementDoc currentRequirementDoc(String workflowRunId) {
        WorkflowRuntimeSnapshot snapshot = workflowUseCase.getRuntimeSnapshot(workflowRunId);
        return snapshot.requirementDoc()
                .orElseThrow(() -> new IllegalStateException("workflow %s has no requirement doc yet".formatted(workflowRunId)));
    }

    private ActorRef humanActor(String actorId) {
        return new ActorRef(ActorType.HUMAN, actorId);
    }
}

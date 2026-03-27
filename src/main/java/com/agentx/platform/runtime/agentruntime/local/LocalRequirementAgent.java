package com.agentx.platform.runtime.agentruntime.local;

import com.agentx.platform.domain.intake.model.RequirementDoc;
import com.agentx.platform.domain.intake.model.RequirementStatus;
import com.agentx.platform.domain.intake.model.RequirementVersion;
import com.agentx.platform.domain.shared.model.JsonPayload;
import com.agentx.platform.runtime.application.workflow.StartCodingWorkflowCommand;
import org.springframework.stereotype.Component;

@Component
public class LocalRequirementAgent {

    public RequirementDraft createConfirmedRequirement(String workflowRunId, String requirementDocId, StartCodingWorkflowCommand command) {
        RequirementDoc requirementDoc = new RequirementDoc(
                requirementDocId,
                workflowRunId,
                1,
                1,
                RequirementStatus.CONFIRMED,
                command.requirementTitle()
        );
        RequirementVersion version = new RequirementVersion(
                requirementDocId,
                1,
                command.requirementContent(),
                command.createdBy()
        );
        return new RequirementDraft(
                requirementDoc,
                version,
                new JsonPayload("""
                        {
                          "status":"confirmed",
                          "source":"local-requirement-agent"
                        }
                        """)
        );
    }

    public record RequirementDraft(
            RequirementDoc requirementDoc,
            RequirementVersion requirementVersion,
            JsonPayload summary
    ) {
    }
}

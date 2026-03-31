package com.agentx.platform.controlplane.api.workflow;

import com.agentx.platform.controlplane.application.WorkflowCommandFacade;
import com.agentx.platform.controlplane.application.WorkflowCommandResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/controlplane/workflows")
public class WorkflowCommandController {

    private final WorkflowCommandFacade workflowCommandFacade;

    public WorkflowCommandController(WorkflowCommandFacade workflowCommandFacade) {
        this.workflowCommandFacade = workflowCommandFacade;
    }

    @PostMapping
    public WorkflowCommandResult startWorkflow(@Valid @RequestBody StartWorkflowRequest request) {
        return workflowCommandFacade.startWorkflow(
                request.title(),
                request.requirementTitle(),
                request.requirementContent(),
                request.profileId(),
                request.createdByActorId(),
                request.autoAgentMode()
        );
    }

    @PostMapping("/{workflowRunId}/drive")
    public WorkflowCommandResult driveWorkflow(@PathVariable String workflowRunId) {
        return workflowCommandFacade.driveWorkflow(workflowRunId);
    }

    @PutMapping("/{workflowRunId}/requirement/current")
    public WorkflowCommandResult editRequirement(
            @PathVariable String workflowRunId,
            @Valid @RequestBody EditRequirementRequest request
    ) {
        return workflowCommandFacade.editRequirement(
                workflowRunId,
                request.docId(),
                request.title(),
                request.content(),
                request.editedByActorId()
        );
    }

    @PostMapping("/{workflowRunId}/requirement/confirm")
    public WorkflowCommandResult confirmRequirement(
            @PathVariable String workflowRunId,
            @Valid @RequestBody ConfirmRequirementRequest request
    ) {
        return workflowCommandFacade.confirmRequirement(
                workflowRunId,
                request.docId(),
                request.version(),
                request.confirmedByActorId()
        );
    }

    public record StartWorkflowRequest(
            @NotBlank String title,
            @NotBlank String requirementTitle,
            @NotBlank String requirementContent,
            @NotBlank String profileId,
            @NotBlank String createdByActorId,
            boolean autoAgentMode
    ) {
    }

    public record EditRequirementRequest(
            @NotBlank String docId,
            @NotBlank String title,
            @NotBlank String content,
            @NotBlank String editedByActorId
    ) {
    }

    public record ConfirmRequirementRequest(
            @NotBlank String docId,
            @Min(1) int version,
            @NotBlank String confirmedByActorId
    ) {
    }
}

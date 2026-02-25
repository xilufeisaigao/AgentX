package com.agentx.agentxbackend.mergegate.api;

import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MergeGateController {

    private final MergeGateUseCase useCase;

    public MergeGateController(MergeGateUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v0/foreman/tasks/{taskId}/merge-gate/start")
    public ResponseEntity<MergeGateStartResponse> start(@PathVariable String taskId) {
        MergeGateResult result = useCase.start(taskId);
        MergeGateStartResponse body = new MergeGateStartResponse(
            result.taskId(),
            result.verifyRunId(),
            result.accepted(),
            result.message()
        );
        if (!result.accepted()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return ResponseEntity.ok(body);
    }

    public record MergeGateStartResponse(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("verify_run_id") String verifyRunId,
        boolean accepted,
        String message
    ) {
    }
}

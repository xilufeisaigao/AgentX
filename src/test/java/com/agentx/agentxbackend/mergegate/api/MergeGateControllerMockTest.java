package com.agentx.agentxbackend.mergegate.api;

import com.agentx.agentxbackend.execution.application.PreconditionFailedException;
import com.agentx.agentxbackend.mergegate.application.port.in.MergeGateUseCase;
import com.agentx.agentxbackend.mergegate.domain.model.MergeGateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MergeGateControllerMockTest {

    @Mock
    private MergeGateUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MergeGateController controller = new MergeGateController(useCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new MergeGateExceptionHandler())
            .build();
    }

    @Test
    void startShouldReturnOkWhenAccepted() throws Exception {
        when(useCase.start("TASK-1")).thenReturn(
            new MergeGateResult("TASK-1", "RUN-VERIFY-1", true, "ok")
        );

        mockMvc.perform(post("/api/v0/foreman/tasks/TASK-1/merge-gate/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.task_id").value("TASK-1"))
            .andExpect(jsonPath("$.verify_run_id").value("RUN-VERIFY-1"))
            .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void startShouldReturnConflictWhenLaneBusy() throws Exception {
        when(useCase.start("TASK-2")).thenReturn(
            new MergeGateResult("TASK-2", null, false, "busy")
        );

        mockMvc.perform(post("/api/v0/foreman/tasks/TASK-2/merge-gate/start"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.task_id").value("TASK-2"))
            .andExpect(jsonPath("$.accepted").value(false));
    }

    @Test
    void startShouldReturnPreconditionFailedWhenVerifyAlreadyFailed() throws Exception {
        when(useCase.start("TASK-3")).thenThrow(new PreconditionFailedException("verify already failed"));

        mockMvc.perform(post("/api/v0/foreman/tasks/TASK-3/merge-gate/start"))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.message").value("verify already failed"));
    }
}

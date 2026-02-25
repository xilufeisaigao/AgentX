package com.agentx.agentxbackend.process.api;

import com.agentx.agentxbackend.process.application.port.in.RuntimeLlmConfigUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuntimeLlmConfigControllerMockTest {

    @Mock
    private RuntimeLlmConfigUseCase runtimeLlmConfigUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RuntimeLlmConfigController controller = new RuntimeLlmConfigController(runtimeLlmConfigUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetCurrentRuntimeConfig() throws Exception {
        when(runtimeLlmConfigUseCase.getCurrentConfig()).thenReturn(
            new RuntimeLlmConfigUseCase.RuntimeConfigView(
                "zh-CN",
                new RuntimeLlmConfigUseCase.LlmProfile(
                    "mock",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    "",
                    120000
                ),
                new RuntimeLlmConfigUseCase.LlmProfile(
                    "mock",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    "",
                    120000
                ),
                3L,
                true
            )
        );

        mockMvc.perform(get("/api/v0/runtime/llm-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.output_language").value("zh-CN"))
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.requirement_llm.provider").value("mock"))
            .andExpect(jsonPath("$.worker_runtime_llm.model").value("qwen3.5-plus-2026-02-15"));
    }

    @Test
    void shouldProbeRuntimeConfig() throws Exception {
        when(runtimeLlmConfigUseCase.probe(any())).thenReturn(
            new RuntimeLlmConfigUseCase.ConnectivityProbeResult(
                "zh-CN",
                new RuntimeLlmConfigUseCase.ProbeItem(
                    true,
                    "bailian",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    120000,
                    350,
                    "probe ok",
                    null
                ),
                new RuntimeLlmConfigUseCase.ProbeItem(
                    true,
                    "bailian",
                    "langchain4j",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3.5-plus-2026-02-15",
                    120000,
                    280,
                    "probe ok",
                    null
                ),
                true,
                2L,
                true
            )
        );

        mockMvc.perform(post("/api/v0/runtime/llm-config:test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "output_language":"zh-CN",
                      "requirement_llm":{"provider":"bailian"},
                      "worker_runtime_llm":{"provider":"bailian"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.all_ok").value(true))
            .andExpect(jsonPath("$.requirement_llm.ok").value(true))
            .andExpect(jsonPath("$.worker_runtime_llm.latency_ms").value(280));
    }

    @Test
    void shouldReturnBadRequestWhenApplyConfigInvalid() throws Exception {
        when(runtimeLlmConfigUseCase.apply(any())).thenThrow(new IllegalArgumentException("api_key is required"));

        mockMvc.perform(post("/api/v0/runtime/llm-config:apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "output_language":"zh-CN",
                      "requirement_llm":{"provider":"bailian","api_key":""}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CONFIG"));
    }
}

package com.labex.labexagent.controller;

import com.labex.labexagent.llm.LlmProviderFactory;
import com.labex.service.AgentModelConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentModelConfigControllerTest {
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AgentModelConfigController(
            mock(AgentModelConfigService.class),
            mock(LlmProviderFactory.class)
    )).build();

    @Test
    void modelListEndpointAcceptsPostRequestsWithoutTreatingPathAsConfigId() throws Exception {
        mockMvc.perform(post("/student/model-configs/model-list")
                        .contentType("application/json")
                        .content("{\"modelsUrl\":\"http://localhost/models\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(false));
    }
}

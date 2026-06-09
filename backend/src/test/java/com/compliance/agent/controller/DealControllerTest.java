package com.compliance.agent.controller;

import com.compliance.agent.agent.DealAnalyzerOrchestrator;
import com.compliance.agent.config.SecurityConfig;
import com.compliance.agent.model.DealModels.DealAnalysisRequest;
import com.compliance.agent.model.DealModels.DealAnalysisResponse;
import com.compliance.agent.model.DealModels.DealDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealController.class)
@Import(SecurityConfig.class)
class DealControllerTest {

    private static final MediaType JSON = Objects.requireNonNull(MediaType.APPLICATION_JSON);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private DealAnalyzerOrchestrator orchestrator;

    private @NonNull String json(Object o) throws Exception {
        return Objects.requireNonNull(objectMapper.writeValueAsString(o));
    }

    @Test
    void analyze_validRequest_returns200() throws Exception {
        DealAnalysisResponse stub = new DealAnalysisResponse(
                "session-1", "BUY", 80, "FIX_AND_FLIP", "70_PERCENT_RULE",
                null, "", List.of(), List.of(), "summary", "recommendation", List.of());
        when(orchestrator.analyzeDeal(any(DealAnalysisRequest.class))).thenReturn(stub);

        mockMvc.perform(post("/api/deal/analyze")
                        .contentType(JSON)
                        .content(json(new DealAnalysisRequest(
                                List.of(new DealDocument("contract.pdf", "text", "wholesale_purchase_agreement")),
                                "123 Main St",
                                100000.0,
                                10000.0,
                                "notes"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("BUY"))
                .andExpect(jsonPath("$.score").value(80));
    }

    @Test
    void analyze_invalidDocumentType_returns400() throws Exception {
        mockMvc.perform(post("/api/deal/analyze")
                        .contentType(JSON)
                        .content(json(new DealAnalysisRequest(
                                List.of(new DealDocument("contract.pdf", "text", "bad type")),
                                "123 Main St",
                                100000.0,
                                10000.0,
                                "notes"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }
}

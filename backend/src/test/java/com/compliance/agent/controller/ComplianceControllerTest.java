package com.compliance.agent.controller;

import com.compliance.agent.agent.ComplianceAgentOrchestrator;
import com.compliance.agent.model.Models.*;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComplianceController.class)
class ComplianceControllerTest {

    // Helpers: wrap static constants and methods whose return type Eclipse treats as
    // potentially-null, so every test body stays readable without per-call requireNonNull.
    private static final MediaType JSON = Objects.requireNonNull(MediaType.APPLICATION_JSON);

    private String json(Object o) throws Exception {
        return Objects.requireNonNull(objectMapper.writeValueAsString(o));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private ComplianceAgentOrchestrator orchestrator;
    @MockBean  private ConversationMemoryService conversationMemoryService;
    @MockBean  private RagService ragService;

    // ── POST /api/compliance/analyze ─────────────────────────────────────────

    @Test
    void analyze_validRequest_returns200WithSessionId() throws Exception {
        AnalyzeResponse stub = new AnalyzeResponse(
                "session-abc", "HIGH", "High-risk contract.", List.of(), List.of());
        when(orchestrator.orchestrateAnalysis(anyString(), anyString(), anyString()))
                .thenReturn(stub);

        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .header("X-Tenant-ID", "tenant-1")
                        .content(json(new AnalyzeRequest("Contract text here.", "nda", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-abc"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.summary").value("High-risk contract."));
    }

    @Test
    void analyze_missingDocumentText_returns400() throws Exception {
        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .content("{\"documentType\":\"nda\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }

    @Test
    void analyze_missingDocumentType_returns400() throws Exception {
        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .content("{\"documentText\":\"some text\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }

    @Test
    void analyze_tenantIdFromHeaderWhenNotInBody() throws Exception {
        AnalyzeResponse stub = new AnalyzeResponse("s1", "LOW", "OK.", List.of(), List.of());
        when(orchestrator.orchestrateAnalysis(anyString(), anyString(), eq("header-tenant")))
                .thenReturn(stub);

        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .header("X-Tenant-ID", "header-tenant")
                        .content(json(new AnalyzeRequest("text", "nda", null))))
                .andExpect(status().isOk());

        verify(orchestrator).orchestrateAnalysis("text", "nda", "header-tenant");
    }

    @Test
    void analyze_tenantIdInBodyOverridesHeader() throws Exception {
        AnalyzeResponse stub = new AnalyzeResponse("s1", "LOW", "OK.", List.of(), List.of());
        when(orchestrator.orchestrateAnalysis(anyString(), anyString(), eq("body-tenant")))
                .thenReturn(stub);

        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .header("X-Tenant-ID", "header-tenant")
                        .content(json(new AnalyzeRequest("text", "nda", "body-tenant"))))
                .andExpect(status().isOk());

        verify(orchestrator).orchestrateAnalysis("text", "nda", "body-tenant");
    }

    @Test
    void analyze_orchestratorThrows_returns500() throws Exception {
        when(orchestrator.orchestrateAnalysis(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("ChromaDB unavailable"));

        mockMvc.perform(post("/api/compliance/analyze")
                        .contentType(JSON)
                        .content(json(new AnalyzeRequest("text", "nda", null))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
    }

    // ── POST /api/compliance/ask ─────────────────────────────────────────────

    @Test
    void ask_validRequest_returns200WithAnswer() throws Exception {
        AskResponse stub = new AskResponse(
                "Payment is due at closing.", "HIGH", List.of(), List.of(), List.of());
        when(orchestrator.orchestrateQA(eq("session-abc"), anyString(), anyString()))
                .thenReturn(stub);

        mockMvc.perform(post("/api/compliance/ask")
                        .contentType(JSON)
                        .content(json(new AskRequest("session-abc", "What are payment terms?", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Payment is due at closing."))
                .andExpect(jsonPath("$.confidence").value("HIGH"));
    }

    @Test
    void ask_missingSessionId_returns400() throws Exception {
        mockMvc.perform(post("/api/compliance/ask")
                        .contentType(JSON)
                        .content("{\"question\":\"What are payment terms?\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }

    @Test
    void ask_missingQuestion_returns400() throws Exception {
        mockMvc.perform(post("/api/compliance/ask")
                        .contentType(JSON)
                        .content("{\"sessionId\":\"abc\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orchestrator);
    }

    // ── DELETE /api/compliance/session/{id} ──────────────────────────────────

    @Test
    void clearSession_returns204() throws Exception {
        mockMvc.perform(delete("/api/compliance/session/session-abc"))
                .andExpect(status().isNoContent());

        verify(conversationMemoryService).clearSession("session-abc");
        verify(ragService).deleteSession("session-abc");
    }
}

package com.compliance.agent.agent;

import com.compliance.agent.model.Models.AnalyzeResponse;
import com.compliance.agent.model.Models.AskResponse;
import com.compliance.agent.service.ChatGenerationService;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.LangSmithService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.service.RerankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceAgentOrchestratorTest {

    @Mock private ChatGenerationService chatGenerationService;
    @Mock private RagService ragService;
    @Mock private RerankService rerankService;
    @Mock private ConversationMemoryService conversationMemoryService;
    @Mock private LangSmithService langSmithService;

    private ComplianceAgentOrchestrator orchestrator;

    private static final String VALID_ANALYZE_JSON = """
            {
              "riskLevel": "HIGH",
              "summary": "High-risk wholesale purchase agreement.",
              "findings": [
                {
                  "clause": "Default clause",
                  "risk": "HIGH",
                  "explanation": "Liquidated damages only.",
                  "confidence": "HIGH"
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        orchestrator = new ComplianceAgentOrchestrator(
                chatGenerationService, ragService, rerankService,
                conversationMemoryService, langSmithService,
                new ObjectMapper()
        );
    }

    // ── orchestrateAnalysis ───────────────────────────────────────────────────

    @Test
    void analyzeHappyPath_returnsRiskLevelAndFindings() {
        // call 1 = guardrail, call 2 = analyze
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", VALID_ANALYZE_JSON);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                "This is a wholesale purchase agreement.", "wholesale_purchase_agreement", "tenant-1");

        assertThat(response.riskLevel()).isEqualTo("HIGH");
        assertThat(response.summary()).contains("High-risk");
        assertThat(response.findings()).hasSize(1);
        assertThat(response.findings().get(0).clause()).isEqualTo("Default clause");
        assertThat(response.sessionId()).isNotBlank();
    }

    @Test
    void analyzeBlockedByGuardrail_returnsBlockedResponse() {
        when(chatGenerationService.generate(anyString())).thenReturn("INVALID");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                "What is the weather today?", "nda", "tenant-1");

        assertThat(response.riskLevel()).isEqualTo("BLOCKED");
        assertThat(response.summary()).containsIgnoringCase("guardrail");
        verify(ragService, never()).ingestDocument(anyString(), anyString());
    }

    @Test
    void analyzeCallsIngestBeforeAnalyze() {
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", VALID_ANALYZE_JSON);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        orchestrator.orchestrateAnalysis("Contract text", "nda", "tenant-1");

        verify(ragService, times(1)).ingestDocument(anyString(), eq("Contract text"));
    }

    @Test
    void analyzeClearsConversationMemoryOnNewSession() {
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", VALID_ANALYZE_JSON);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        orchestrator.orchestrateAnalysis("Contract text", "nda", "tenant-1");

        verify(conversationMemoryService, times(1)).clearSession(anyString());
    }

    @Test
    void analyzeMalformedJsonFallsBackGracefully() {
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", "not json at all");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                "Contract text", "nda", "tenant-1");

        assertThat(response.riskLevel()).isEqualTo("UNKNOWN");
        assertThat(response.findings()).isEmpty();
    }

    @Test
    void analyzeStripsMarkdownFencesBeforeJsonParse() {
        String fenced = "```json\n" + VALID_ANALYZE_JSON + "\n```";
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", fenced);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AnalyzeResponse response = orchestrator.orchestrateAnalysis(
                "Contract", "wholesale_purchase_agreement", "t1");

        assertThat(response.riskLevel()).isEqualTo("HIGH");
    }

    @Test
    void analyzeEmptyFindingsArrayHandledGracefully() {
        String emptyFindings = """
                {"riskLevel":"LOW","summary":"Clean contract.","findings":[]}
                """;
        when(chatGenerationService.generate(anyString())).thenReturn("VALID", emptyFindings);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AnalyzeResponse response = orchestrator.orchestrateAnalysis("Clean doc", "nda", "t1");

        assertThat(response.riskLevel()).isEqualTo("LOW");
        assertThat(response.findings()).isEmpty();
    }

    // ── orchestrateQA ─────────────────────────────────────────────────────────

    @Test
    void qaHappyPath_returnsAnswerAndStoresMemory() {
        EmbeddingMatch<TextSegment> match = candidateMatch("Payment is due at closing.");
        RerankService.RankedMatch ranked = new RerankService.RankedMatch(match, 0, 0.9, 0.95);

        // call 1 = guardrail, call 2 = QA answer
        when(chatGenerationService.generate(anyString()))
                .thenReturn("VALID", "Payment terms require full payment at closing. Confidence: HIGH");
        when(ragService.retrieve(anyString(), anyString())).thenReturn(List.of(match));
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(ranked));
        when(conversationMemoryService.getFormattedHistory(anyString())).thenReturn("No previous conversation.");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AskResponse response = orchestrator.orchestrateQA(
                "session-abc", "What are the payment terms?", "tenant-1");

        assertThat(response.answer()).contains("Payment terms");
        assertThat(response.confidence()).isEqualTo("HIGH");
        assertThat(response.sources()).hasSize(1);
        verify(conversationMemoryService).addUserMessage(eq("session-abc"), anyString());
        verify(conversationMemoryService).addAiMessage(eq("session-abc"), anyString());
    }

    @Test
    void qaBlockedByGuardrail_returnsPoliteRefusal() {
        when(chatGenerationService.generate(anyString())).thenReturn("INVALID");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AskResponse response = orchestrator.orchestrateQA(
                "session-abc", "What is 2 + 2?", "tenant-1");

        assertThat(response.answer()).containsIgnoringCase("only answer questions");
        assertThat(response.confidence()).isEqualTo("LOW");
        verify(ragService, never()).retrieve(anyString(), anyString());
    }

    @Test
    void qaExtractsLowConfidenceCorrectly() {
        EmbeddingMatch<TextSegment> match = candidateMatch("Chunk");
        RerankService.RankedMatch ranked = new RerankService.RankedMatch(match, 0, 0.5, 0.5);

        when(chatGenerationService.generate(anyString())).thenReturn("VALID", "Unclear. Confidence: LOW");
        when(ragService.retrieve(anyString(), anyString())).thenReturn(List.of(match));
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(ranked));
        when(conversationMemoryService.getFormattedHistory(anyString())).thenReturn("");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AskResponse response = orchestrator.orchestrateQA("s", "q", "t");
        assertThat(response.confidence()).isEqualTo("LOW");
    }

    @Test
    void qaDefaultsToMediumConfidenceWhenAbsent() {
        EmbeddingMatch<TextSegment> match = candidateMatch("Chunk");
        RerankService.RankedMatch ranked = new RerankService.RankedMatch(match, 0, 0.7, 0.7);

        when(chatGenerationService.generate(anyString())).thenReturn("VALID", "Some answer with no confidence tag.");
        when(ragService.retrieve(anyString(), anyString())).thenReturn(List.of(match));
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(ranked));
        when(conversationMemoryService.getFormattedHistory(anyString())).thenReturn("");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        AskResponse response = orchestrator.orchestrateQA("s", "q", "t");
        assertThat(response.confidence()).isEqualTo("MEDIUM");
    }

    @Test
    void qaInjectsConversationHistoryIntoPrompt() {
        EmbeddingMatch<TextSegment> match = candidateMatch("Chunk");
        RerankService.RankedMatch ranked = new RerankService.RankedMatch(match, 0, 0.8, 0.8);

        when(chatGenerationService.generate(anyString())).thenReturn("VALID", "Answer. Confidence: MEDIUM");
        when(ragService.retrieve(anyString(), anyString())).thenReturn(List.of(match));
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(ranked));
        when(conversationMemoryService.getFormattedHistory("session-123"))
                .thenReturn("User: Prior question\nAssistant: Prior answer");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        orchestrator.orchestrateQA("session-123", "follow-up?", "t");

        // verify getFormattedHistory was called with the correct session
        verify(conversationMemoryService).getFormattedHistory("session-123");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static EmbeddingMatch<TextSegment> candidateMatch(String text) {
        Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        return new EmbeddingMatch<>(0.9, "id-1", embedding, TextSegment.from(text));
    }
}

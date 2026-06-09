package com.compliance.agent.agent;

import com.compliance.agent.model.DealModels.DealAnalysisRequest;
import com.compliance.agent.model.DealModels.DealAnalysisResponse;
import com.compliance.agent.model.DealModels.DealDocument;
import com.compliance.agent.service.LangSmithService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.service.WebSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealAnalyzerOrchestratorTest {

    private static final String WEIRD_JSON = """
            {
              "verdict": "extreme",
              "score": 130,
              "strategy": "passive_income",
              "framework": "balanced",
              "financials": {
                "askingPrice": 100000,
                "estimatedRepairs": 10000,
                "estimatedARV": 150000,
                "maxAllowableOffer": 90000,
                "projectedProfit": 50000,
                "roi": "25%\\nextra",
                "projectedMonthlyRent": "$2,000",
                "capRate": "7.1%",
                "cashOnCash": "12%"
              },
              "marketNotes": "Line one\\nLine two",
              "riskFactors": ["First risk\\nsecond line", "Second risk"],
              "complianceFlags": ["Flag one"],
              "summary": "Summary\\nwith new line",
              "recommendation": "Recommendation\\nwith new line"
            }
            """;

    @Mock private OpenAiChatModel chatModel;
    @Mock private RagService ragService;
    @Mock private WebSearchService webSearchService;
    @Mock private LangSmithService langSmithService;

    private DealAnalyzerOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new DealAnalyzerOrchestrator(
                chatModel, ragService, webSearchService, langSmithService, new ObjectMapper());
    }

    @Test
    void analyzeDealNormalizesUnexpectedModelOutput() {
        when(webSearchService.searchMarketData(anyString())).thenReturn("");
        doNothing().when(ragService).ingestDocuments(anyString(), anyList());
        when(chatModel.generate(anyString())).thenReturn(WEIRD_JSON);
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        DealAnalysisResponse response = orchestrator.analyzeDeal(
                new DealAnalysisRequest(
                        List.of(new DealDocument("contract.pdf", "Contract text", "wholesale_purchase_agreement")),
                        "123 Main St",
                        100000.0,
                        10000.0,
                        "Notes"));

        assertThat(response.verdict()).isEqualTo("PASS");
        assertThat(response.score()).isEqualTo(100);
        assertThat(response.strategy()).isEqualTo("UNKNOWN");
        assertThat(response.framework()).isEqualTo("QUALITATIVE");
        assertThat(response.financials()).isNotNull();
        assertThat(response.financials().roi()).isEqualTo("25% extra");
        assertThat(response.marketNotes()).doesNotContain("\n");
        assertThat(response.summary()).doesNotContain("\n");
        assertThat(response.recommendation()).doesNotContain("\n");
        assertThat(response.riskFactors()).containsExactly("First risk second line", "Second risk");
    }

    @Test
    void analyzeDealMalformedJsonFallsBackGracefully() {
        when(webSearchService.searchMarketData(anyString())).thenReturn("");
        doNothing().when(ragService).ingestDocuments(anyString(), anyList());
        when(chatModel.generate(anyString())).thenReturn("not json");
        when(langSmithService.startRun(anyString(), anyString(), anyMap(), any())).thenReturn("run-id");

        DealAnalysisResponse response = orchestrator.analyzeDeal(
                new DealAnalysisRequest(
                        List.of(new DealDocument("contract.pdf", "Contract text", "wholesale_purchase_agreement")),
                        "123 Main St",
                        100000.0,
                        10000.0,
                        "Notes"));

        assertThat(response.verdict()).isEqualTo("UNKNOWN");
        assertThat(response.recommendation()).isEqualTo("Analysis completed but response could not be parsed.");
    }
}

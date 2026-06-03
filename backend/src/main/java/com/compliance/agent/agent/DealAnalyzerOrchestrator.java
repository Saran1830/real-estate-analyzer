package com.compliance.agent.agent;

import com.compliance.agent.model.DealModels.*;
import com.compliance.agent.model.Models.NodeExecution;
import com.compliance.agent.prompt.DealPromptTemplates;
import com.compliance.agent.service.LangSmithService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.service.WebSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DealAnalyzerOrchestrator {

    private final OpenAiChatModel chatModel;
    private final RagService ragService;
    private final WebSearchService webSearchService;
    private final LangSmithService langSmithService;
    private final ObjectMapper objectMapper;

    public DealAnalysisResponse analyzeDeal(DealAnalysisRequest request) {
        String sessionId = UUID.randomUUID().toString();
        List<NodeExecution> trace = new ArrayList<>();

        String parentRunId = langSmithService.startRun("deal-analysis", "chain",
                Map.of("address", request.address() != null ? request.address() : "",
                        "docCount", request.documents().size()), null);

        // Node 1 — optional web search for market data
        String marketData = searchNode(request.address(), trace, parentRunId);

        // Node 2 — ingest all documents into ChromaDB for Q&A
        ingestNode(sessionId, request.documents(), trace, parentRunId);

        // Node 3 — deal analysis via LLM
        DealAnalysisResponse result = analyzeNode(sessionId, request, marketData, trace, parentRunId);

        langSmithService.endRun(parentRunId, Map.of("verdict", result.verdict(), "score", result.score()), null);
        return result;
    }

    private String searchNode(String address, List<NodeExecution> trace, String parentRunId) {
        if (address == null || address.isBlank()) {
            trace.add(new NodeExecution("web_search", "SKIPPED", 0, "No address provided"));
            return "";
        }
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("web_search", "tool", Map.of("address", address), parentRunId);
        String data = webSearchService.searchMarketData(address);
        long latency = System.currentTimeMillis() - start;
        String status = data.isEmpty() ? "NO_RESULTS" : "OK";
        trace.add(new NodeExecution("web_search", status, latency,
                data.isEmpty() ? "No results (Tavily key not set or no data found)" : "Market data retrieved"));
        langSmithService.endRun(runId, Map.of("found", !data.isEmpty()), null);
        return data;
    }

    private void ingestNode(String sessionId, List<DealDocument> documents,
                             List<NodeExecution> trace, String parentRunId) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("ingest", "tool",
                Map.of("sessionId", sessionId, "docCount", documents.size()), parentRunId);
        try {
            ragService.ingestDocuments(sessionId, documents);
            long latency = System.currentTimeMillis() - start;
            trace.add(new NodeExecution("ingest", "OK", latency,
                    documents.size() + " document(s) ingested"));
            langSmithService.endRun(runId, Map.of("status", "ok", "docs", documents.size()), null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            trace.add(new NodeExecution("ingest", "ERROR", latency, e.getMessage()));
            langSmithService.endRun(runId, null, e.getMessage());
            log.error("Deal ingest failed for session={}: {}", sessionId, e.getMessage(), e);
        }
    }

    private DealAnalysisResponse analyzeNode(String sessionId, DealAnalysisRequest request,
                                              String marketData, List<NodeExecution> trace,
                                              String parentRunId) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("deal_analyze", "llm",
                Map.of("docCount", request.documents().size()), parentRunId);

        String propertyContext = buildPropertyContext(request);
        String marketSection = marketData.isEmpty() ? "" :
                "Market Data (live web search):\n" + marketData + "\n";
        String docsText = request.documents().stream()
                .map(d -> "=== " + d.name() + " [" + d.type() + "] ===\n" + truncate(d.text(), 4000))
                .collect(Collectors.joining("\n\n"));

        String prompt = DealPromptTemplates.DEAL_ANALYSIS_PROMPT
                .replace("{propertyContext}", propertyContext)
                .replace("{marketData}", marketSection)
                .replace("{docCount}", String.valueOf(request.documents().size()))
                .replace("{documents}", docsText);

        String llmResponse = chatModel.generate(prompt);
        String cleaned = stripMarkdownFences(llmResponse);
        long latency = System.currentTimeMillis() - start;

        try {
            JsonNode json = objectMapper.readTree(cleaned);
            String verdict = json.path("verdict").asText("PASS");
            int score = json.path("score").asInt(50);

            trace.add(new NodeExecution("deal_analyze", "OK", latency,
                    "Verdict: " + verdict + " | Score: " + score));
            langSmithService.endRun(runId, Map.of("verdict", verdict, "score", score), null);

            return new DealAnalysisResponse(
                    sessionId, verdict, score,
                    json.path("strategy").asText("UNKNOWN"),
                    json.path("framework").asText("QUALITATIVE"),
                    parseFinancials(json.path("financials")),
                    json.path("marketNotes").asText(""),
                    parseStringList(json.path("riskFactors")),
                    parseStringList(json.path("complianceFlags")),
                    json.path("summary").asText(""),
                    json.path("recommendation").asText(""),
                    trace
            );

        } catch (JsonProcessingException e) {
            log.error("Failed to parse deal analysis response: {}", e.getMessage());
            trace.add(new NodeExecution("deal_analyze", "PARSE_ERROR", latency, e.getMessage()));
            langSmithService.endRun(runId, null, e.getMessage());
            return new DealAnalysisResponse(sessionId, "UNKNOWN", 0, "UNKNOWN", "QUALITATIVE",
                    null, "", List.of(), List.of(),
                    "Analysis completed but response could not be parsed.", llmResponse, trace);
        }
    }

    private String buildPropertyContext(DealAnalysisRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.address() != null && !req.address().isBlank())
            sb.append("Address: ").append(req.address()).append("\n");
        if (req.askingPrice() != null)
            sb.append("Asking Price: $").append(String.format("%,.0f", req.askingPrice())).append("\n");
        if (req.estimatedRepairs() != null)
            sb.append("Estimated Repairs: $").append(String.format("%,.0f", req.estimatedRepairs())).append("\n");
        if (req.notes() != null && !req.notes().isBlank())
            sb.append("Notes: ").append(req.notes()).append("\n");
        return sb.isEmpty() ? "No property context provided — infer from documents." : sb.toString().trim();
    }

    private DealFinancials parseFinancials(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        return new DealFinancials(
                nullableDouble(node, "askingPrice"),
                nullableDouble(node, "estimatedRepairs"),
                nullableDouble(node, "estimatedARV"),
                nullableDouble(node, "maxAllowableOffer"),
                nullableDouble(node, "projectedProfit"),
                nullableString(node, "roi"),
                nullableString(node, "projectedMonthlyRent"),
                nullableString(node, "capRate"),
                nullableString(node, "cashOnCash")
        );
    }

    private Double nullableDouble(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return (n.isNull() || n.isMissingNode()) ? null : n.asDouble();
    }

    private String nullableString(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return (n.isNull() || n.isMissingNode()) ? null : n.asText(null);
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> result.add(item.asText()));
        return result;
    }

    private static String stripMarkdownFences(String text) {
        return text.replaceAll("```(?:json)?\\s*", "").replaceAll("```", "").trim();
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}

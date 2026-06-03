package com.compliance.agent.agent;

import com.compliance.agent.model.Models.*;
import com.compliance.agent.prompt.PromptTemplates;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.LangSmithService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.service.RerankService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceAgentOrchestrator {

    private final OpenAiChatModel chatModel;
    private final RagService ragService;
    private final RerankService rerankService;
    private final ConversationMemoryService conversationMemoryService;
    private final LangSmithService langSmithService;
    private final ObjectMapper objectMapper;

    public AnalyzeResponse orchestrateAnalysis(String documentText, String documentType, String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        List<NodeExecution> trace = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        String parentRunId = langSmithService.startRun(
                "compliance-analysis", "chain",
                Map.of("documentType", documentType, "sessionId", sessionId), null);

        // Node 1 — Guardrail
        AgentState state = AgentState.forAnalysis(sessionId, tenantId, documentText, documentType);
        state = guardrailNode(state, trace, parentRunId);

        if (state.guardrailStatus() == AgentState.GuardrailStatus.INVALID) {
            langSmithService.endRun(parentRunId, Map.of("blocked", true, "reason", state.guardrailReason()), null);
            return new AnalyzeResponse(sessionId, "BLOCKED",
                    "Document rejected by guardrail: " + state.guardrailReason(),
                    List.of(), trace);
        }

        // Node 2 — Ingest (also clears any previous memory for this session)
        conversationMemoryService.clearSession(sessionId);
        state = ingestNode(state, trace, parentRunId);

        // Node 3 — Analyze
        AnalyzeResponse result = analyzeNode(state, trace, parentRunId, totalStart);

        langSmithService.endRun(parentRunId,
                Map.of("sessionId", sessionId, "riskLevel", result.riskLevel(),
                        "findingCount", result.findings().size()), null);
        return result;
    }

    public AskResponse orchestrateQA(String sessionId, String question, String tenantId) {
        List<NodeExecution> trace = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        String parentRunId = langSmithService.startRun(
                "compliance-qa", "chain",
                Map.of("sessionId", sessionId, "question", question), null);

        AgentState state = AgentState.forQA(sessionId, tenantId, question);

        // Guardrail — validates the question is document-related
        state = guardrailNode(state, trace, parentRunId);
        if (state.guardrailStatus() == AgentState.GuardrailStatus.INVALID) {
            langSmithService.endRun(parentRunId, Map.of("blocked", true), null);
            return new AskResponse(
                    "I can only answer questions about the document you uploaded. " + state.guardrailReason(),
                    "LOW", List.of(), List.of(), trace);
        }

        AskResponse response = qaNode(state, trace, parentRunId, totalStart);
        langSmithService.endRun(parentRunId, Map.of("confidence", response.confidence()), null);
        return response;
    }

    // ── Nodes ─────────────────────────────────────────────────────────────────

    private AgentState guardrailNode(AgentState state, List<NodeExecution> trace, String parentRunId) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("guardrail", "llm",
                Map.of("input", state.question() != null ? state.question() : state.documentType()), parentRunId);

        String input = state.question() != null ? state.question()
                : "Document type: " + state.documentType() + ". Review compliance document.";

        String prompt = PromptTemplates.GUARDRAIL_PROMPT.replace("{input}", input);
        String verdict = chatModel.generate(prompt).trim().toUpperCase();
        boolean valid = verdict.startsWith("VALID");

        AgentState.GuardrailStatus status = valid
                ? AgentState.GuardrailStatus.VALID
                : AgentState.GuardrailStatus.INVALID;
        String reason = valid ? null : "Input is off-topic for compliance document review";

        long latency = System.currentTimeMillis() - start;
        trace.add(new NodeExecution("guardrail", valid ? "VALID" : "INVALID", latency, verdict));
        langSmithService.endRun(runId, Map.of("verdict", verdict, "valid", valid), null);

        return state.withGuardrail(status, reason);
    }

    private AgentState ingestNode(AgentState state, List<NodeExecution> trace, String parentRunId) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("ingest", "tool",
                Map.of("sessionId", state.sessionId(), "documentType", state.documentType()), parentRunId);
        try {
            ragService.ingestDocument(state.sessionId(), state.documentText());
            long latency = System.currentTimeMillis() - start;
            trace.add(new NodeExecution("ingest", "OK", latency, "Document chunked and embedded"));
            langSmithService.endRun(runId, Map.of("status", "ok"), null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            trace.add(new NodeExecution("ingest", "ERROR", latency, e.getMessage()));
            langSmithService.endRun(runId, null, e.getMessage());
            log.error("Ingest failed for session={}: {}", state.sessionId(), e.getMessage(), e);
        }
        return state;
    }

    private AnalyzeResponse analyzeNode(AgentState state, List<NodeExecution> trace,
                                         String parentRunId, long totalStart) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("analyze", "llm",
                Map.of("documentType", state.documentType()), parentRunId);

        String promptTemplate = PromptTemplates.useRealEstatePrompt(state.documentType())
                ? PromptTemplates.REAL_ESTATE_ANALYZE_PROMPT
                : PromptTemplates.ANALYZE_PROMPT;

        String prompt = promptTemplate
                .replace("{documentType}", state.documentType())
                .replace("{document}", truncate(state.documentText(), 12000));

        String llmResponse = chatModel.generate(prompt);
        String cleaned = stripMarkdownFences(llmResponse);

        long latency = System.currentTimeMillis() - start;

        try {
            JsonNode json = objectMapper.readTree(cleaned);
            String riskLevel = json.path("riskLevel").asText("MEDIUM");
            String summary = json.path("summary").asText("");
            List<Finding> findings = parseFindings(json);

            trace.add(new NodeExecution("analyze", "OK", latency,
                    "Risk: " + riskLevel + ", Findings: " + findings.size()));
            langSmithService.endRun(runId, Map.of("riskLevel", riskLevel, "findings", findings.size()), null);

            return new AnalyzeResponse(state.sessionId(), riskLevel, summary, findings, trace);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse analyze response for session={}: {}", state.sessionId(), e.getMessage());
            trace.add(new NodeExecution("analyze", "PARSE_ERROR", latency, e.getMessage()));
            langSmithService.endRun(runId, null, "JSON parse error: " + e.getMessage());
            return new AnalyzeResponse(state.sessionId(), "UNKNOWN",
                    "Analysis completed but response could not be parsed.", List.of(), trace);
        }
    }

    private AskResponse qaNode(AgentState state, List<NodeExecution> trace,
                                String parentRunId, long totalStart) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("qa", "chain",
                Map.of("sessionId", state.sessionId(), "question", state.question()), parentRunId);

        // Step 1: retrieve top-20 by cosine similarity
        List<EmbeddingMatch<TextSegment>> candidates = ragService.retrieve(state.sessionId(), state.question());

        // Step 2: Cohere re-rank to top-5
        List<RerankService.RankedMatch> reranked = rerankService.rerank(state.question(), candidates, 5);

        // Step 3: build context from re-ranked chunks
        String context = reranked.stream()
                .map(rm -> rm.match().embedded().text())
                .reduce("", (a, b) -> a + "\n---\n" + b);

        // Step 4: inject conversation history
        String history = conversationMemoryService.getFormattedHistory(state.sessionId());

        // Step 5: fill memory-aware prompt
        String prompt = PromptTemplates.QA_PROMPT
                .replace("{history}", history)
                .replace("{context}", context)
                .replace("{question}", state.question());

        String llmResponse = chatModel.generate(prompt);

        // Step 6: store this turn in memory
        conversationMemoryService.addUserMessage(state.sessionId(), state.question());
        conversationMemoryService.addAiMessage(state.sessionId(), extractAnswer(llmResponse));

        String confidence = extractConfidence(llmResponse);
        List<SourceChunk> sources = buildSourceChunks(reranked);
        List<RerankScore> rerankScores = buildRerankScores(reranked);

        long latency = System.currentTimeMillis() - start;
        trace.add(new NodeExecution("qa", "OK", latency,
                "Candidates: " + candidates.size() + ", Re-ranked: " + reranked.size()));

        langSmithService.endRun(runId,
                Map.of("confidence", confidence,
                        "candidateCount", candidates.size(),
                        "rerankCount", reranked.size()), null);

        return new AskResponse(extractAnswer(llmResponse), confidence, sources, rerankScores, trace);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Finding> parseFindings(JsonNode json) {
        List<Finding> findings = new ArrayList<>();
        JsonNode arr = json.path("findings");
        if (arr.isArray()) {
            for (JsonNode f : arr) {
                findings.add(new Finding(
                        f.path("clause").asText(""),
                        f.path("risk").asText("MEDIUM"),
                        f.path("explanation").asText(""),
                        f.path("confidence").asText("MEDIUM")
                ));
            }
        }
        return findings;
    }

    private List<SourceChunk> buildSourceChunks(List<RerankService.RankedMatch> reranked) {
        return reranked.stream()
                .map(rm -> new SourceChunk(
                        rm.match().embedded().text(),
                        rm.cosineScore(),
                        rm.rerankScore()))
                .toList();
    }

    private List<RerankScore> buildRerankScores(List<RerankService.RankedMatch> reranked) {
        return reranked.stream()
                .map(rm -> new RerankScore(
                        rm.originalIndex(),
                        rm.cosineScore(),
                        rm.rerankScore(),
                        truncate(rm.match().embedded().text(), 120)))
                .toList();
    }

    private String extractAnswer(String response) {
        int idx = response.lastIndexOf("Confidence:");
        return idx > 0 ? response.substring(0, idx).trim() : response.trim();
    }

    private String extractConfidence(String response) {
        if (response.contains("Confidence: HIGH")) return "HIGH";
        if (response.contains("Confidence: LOW")) return "LOW";
        return "MEDIUM";
    }

    private static String stripMarkdownFences(String text) {
        return text.replaceAll("```(?:json)?\\s*", "").replaceAll("```", "").trim();
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}

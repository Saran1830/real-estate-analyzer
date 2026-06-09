package com.compliance.agent.agent;

import com.compliance.agent.model.Models.*;
import com.compliance.agent.prompt.PromptTemplates;
import com.compliance.agent.service.ConversationMemoryService;
import com.compliance.agent.service.LangSmithService;
import com.compliance.agent.service.RagService;
import com.compliance.agent.service.RerankService;
import com.compliance.agent.util.LlmUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceAgentOrchestrator {

    private static final int MAX_FINDINGS = 10;
    private static final int MAX_FINDING_TEXT_CHARS = 500;
    private static final int MAX_SUMMARY_CHARS = 1_000;

    private final OpenAiChatModel chatModel;
    private final RagService ragService;
    private final RerankService rerankService;
    private final ConversationMemoryService conversationMemoryService;
    private final LangSmithService langSmithService;
    private final ObjectMapper objectMapper;

    @Value("${rag.rerank.top-n}")
    private int rerankTopN;

    public AnalyzeResponse orchestrateAnalysis(String documentText, String documentType, String tenantId) {
        String sessionId = UUID.randomUUID().toString();
        List<NodeExecution> trace = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        String parentRunId = langSmithService.startRun(
                "compliance-analysis", "chain",
                Map.of("documentType", LlmUtils.safeIdentifier(documentType, 100),
                        "documentLength", documentText == null ? 0 : documentText.length(),
                        "sessionId", sessionId), null);

        // Node 1 — Guardrail
        AgentState state = AgentState.forAnalysis(sessionId, tenantId, documentText, documentType);
        state = guardrailNode(state, trace, parentRunId);

        if (state.guardrailStatus() == AgentState.GuardrailStatus.INVALID) {
            langSmithService.endRun(parentRunId, Map.of("blocked", true,
                    "reason", state.guardrailReason(),
                    "totalLatencyMs", elapsedMs(totalStart)), null);
            return new AnalyzeResponse(sessionId, "BLOCKED",
                    "Document rejected by guardrail: " + state.guardrailReason(),
                    List.of(), trace);
        }

        // Node 2 — Ingest (also clears any previous memory for this session)
        conversationMemoryService.clearSession(sessionId);
        state = ingestNode(state, trace, parentRunId);

        if (state.ingestFailed()) {
            langSmithService.endRun(parentRunId, Map.of("totalLatencyMs", elapsedMs(totalStart)),
                    state.ingestError());
            return new AnalyzeResponse(sessionId, "UNKNOWN",
                    "Document could not be ingested: " + state.ingestError(),
                    List.of(), trace);
        }

        // Node 3 — Analyze
        AnalyzeResponse result = analyzeNode(state, trace, parentRunId, totalStart);

        langSmithService.endRun(parentRunId,
                Map.of("sessionId", sessionId, "riskLevel", result.riskLevel(),
                        "findingCount", result.findings().size(),
                        "totalLatencyMs", elapsedMs(totalStart)), null);
        return result;
    }

    public AskResponse orchestrateQA(String sessionId, String question, String tenantId) {
        List<NodeExecution> trace = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        String parentRunId = langSmithService.startRun(
                "compliance-qa", "chain",
                Map.of("sessionId", sessionId,
                        "questionHash", LlmUtils.sha256Hex(question),
                        "questionLength", question == null ? 0 : question.length()), null);

        AgentState state = AgentState.forQA(sessionId, tenantId, question);

        // Guardrail — validates the question is document-related
        state = guardrailNode(state, trace, parentRunId);
        if (state.guardrailStatus() == AgentState.GuardrailStatus.INVALID) {
            langSmithService.endRun(parentRunId, Map.of("blocked", true,
                    "totalLatencyMs", elapsedMs(totalStart)), null);
            return new AskResponse(
                    "I can only answer questions about the document you uploaded. " + state.guardrailReason(),
                    "LOW", List.of(), List.of(), trace);
        }

        AskResponse response = qaNode(state, trace, parentRunId, totalStart);
        langSmithService.endRun(parentRunId, Map.of("confidence", response.confidence(),
                "totalLatencyMs", elapsedMs(totalStart)), null);
        return response;
    }

    // ── Nodes ─────────────────────────────────────────────────────────────────

    private AgentState guardrailNode(AgentState state, List<NodeExecution> trace, String parentRunId) {
        long start = System.currentTimeMillis();
        String input = state.question() != null ? state.question()
                : "Document type: " + state.documentType() + ". Review compliance document.";
        String runId = langSmithService.startRun("guardrail", "llm",
                Map.of("inputLength", input.length()), parentRunId);

        String prompt = PromptTemplates.GUARDRAIL_PROMPT.replace("{input}",
                LlmUtils.wrapAsUntrustedBlock("INPUT", LlmUtils.truncate(input, 2000)));
        String verdict = LlmUtils.sanitizeText(chatModel.generate(prompt), 200).toUpperCase(Locale.ROOT);
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
                Map.of("sessionId", state.sessionId(),
                        "documentType", LlmUtils.safeIdentifier(state.documentType(), 100)), parentRunId);
        try {
            ragService.ingestDocument(state.sessionId(), state.documentText());
            long latency = System.currentTimeMillis() - start;
            trace.add(new NodeExecution("ingest", "OK", latency, "Document chunked and embedded"));
            langSmithService.endRun(runId, Map.of("status", "ok"), null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String detail = "Document ingestion failed.";
            trace.add(new NodeExecution("ingest", "ERROR", latency, detail));
            langSmithService.endRun(runId, null, detail);
            log.error("Ingest failed for session={}: {}",
                    LlmUtils.safeIdentifier(state.sessionId(), 32), detail, e);
            return state.withIngestFailed(detail);
        }
        return state;
    }

    private AnalyzeResponse analyzeNode(AgentState state, List<NodeExecution> trace,
                                         String parentRunId, long totalStart) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("analyze", "llm",
                Map.of("documentType", LlmUtils.safeIdentifier(state.documentType(), 100),
                        "documentLength", state.documentText() == null ? 0 : state.documentText().length()), parentRunId);

        String promptTemplate = PromptTemplates.useRealEstatePrompt(state.documentType())
                ? PromptTemplates.REAL_ESTATE_ANALYZE_PROMPT
                : PromptTemplates.ANALYZE_PROMPT;

        String prompt = promptTemplate
                .replace("{documentType}", LlmUtils.safeIdentifier(state.documentType(), 100))
                .replace("{document}", LlmUtils.wrapAsUntrustedBlock("DOCUMENT",
                        LlmUtils.sanitizeText(state.documentText(), 12_000)));

        String llmResponse = chatModel.generate(prompt);
        String cleaned = stripMarkdownFences(llmResponse);

        long latency = System.currentTimeMillis() - start;

        try {
            JsonNode json = objectMapper.readTree(cleaned);
            String riskLevel = normalizeRiskLevel(json.path("riskLevel").asText(null));
            String summary = LlmUtils.sanitizeText(json.path("summary").asText(""), MAX_SUMMARY_CHARS);
            List<Finding> findings = parseFindings(json);
            long totalLatencyMs = elapsedMs(totalStart);

            trace.add(new NodeExecution("analyze", "OK", latency,
                    "Risk: " + riskLevel + ", Findings: " + findings.size()
                            + ", End-to-end: " + totalLatencyMs + "ms"));
            langSmithService.endRun(runId, Map.of("riskLevel", riskLevel, "findings", findings.size(),
                    "totalLatencyMs", totalLatencyMs), null);

            return new AnalyzeResponse(state.sessionId(), riskLevel, summary, findings, trace);

        } catch (JsonProcessingException e) {
            String message = LlmUtils.sanitizeForLog(e.getMessage(), 200);
            log.error("Failed to parse analyze response for session={}: {}",
                    LlmUtils.safeIdentifier(state.sessionId(), 32), message);
            long totalLatencyMs = elapsedMs(totalStart);
            trace.add(new NodeExecution("analyze", "PARSE_ERROR", latency,
                    message + ", End-to-end: " + totalLatencyMs + "ms"));
            langSmithService.endRun(runId, Map.of("totalLatencyMs", totalLatencyMs),
                    "JSON parse error: " + message);
            return new AnalyzeResponse(state.sessionId(), "UNKNOWN",
                    "Analysis completed but response could not be parsed.", List.of(), trace);
        }
    }

    private AskResponse qaNode(AgentState state, List<NodeExecution> trace,
                                String parentRunId, long totalStart) {
        long start = System.currentTimeMillis();
        String runId = langSmithService.startRun("qa", "chain",
                Map.of("sessionId", state.sessionId(),
                        "questionHash", LlmUtils.sha256Hex(state.question()),
                        "questionLength", state.question() == null ? 0 : state.question().length()), parentRunId);

        // Step 1: retrieve top-20 by cosine similarity
        List<EmbeddingMatch<TextSegment>> candidates = ragService.retrieve(state.sessionId(), state.question());

        // Step 2: Cohere re-rank to top-5
        List<RerankService.RankedMatch> reranked = rerankService.rerank(state.question(), candidates, rerankTopN);

        // Step 3: build context from re-ranked chunks
        String context = reranked.stream()
                .map(rm -> rm.match().embedded().text())
                .collect(Collectors.joining("\n---\n"));

        // Step 4: inject conversation history
        String history = LlmUtils.wrapAsUntrustedBlock("HISTORY",
                conversationMemoryService.getFormattedHistory(state.sessionId()));

        // Step 5: fill memory-aware prompt
        String prompt = PromptTemplates.QA_PROMPT
                .replace("{history}", history)
                .replace("{context}", LlmUtils.wrapAsUntrustedBlock("CONTEXT", context))
                .replace("{question}", LlmUtils.wrapAsUntrustedBlock("QUESTION",
                        LlmUtils.truncate(state.question(), 2000)));

        String llmResponse = chatModel.generate(prompt);
        String answer = extractAnswer(llmResponse);

        // Step 6: store this turn in memory
        conversationMemoryService.addUserMessage(state.sessionId(), state.question());
        conversationMemoryService.addAiMessage(state.sessionId(), answer);

        String confidence = extractConfidence(llmResponse);
        List<SourceChunk> sources = buildSourceChunks(reranked);
        List<RerankScore> rerankScores = buildRerankScores(reranked);

        long latency = System.currentTimeMillis() - start;
        long totalLatencyMs = elapsedMs(totalStart);
        trace.add(new NodeExecution("qa", "OK", latency,
                "Candidates: " + candidates.size() + ", Re-ranked: " + reranked.size()
                        + ", End-to-end: " + totalLatencyMs + "ms"));

        langSmithService.endRun(runId,
                Map.of("confidence", confidence,
                        "candidateCount", candidates.size(),
                        "rerankCount", reranked.size(),
                        "totalLatencyMs", totalLatencyMs), null);

        return new AskResponse(answer, confidence, sources, rerankScores, trace);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Finding> parseFindings(JsonNode json) {
        List<Finding> findings = new ArrayList<>();
        JsonNode arr = json.path("findings");
        if (arr.isArray()) {
            int count = 0;
            for (JsonNode f : arr) {
                if (count++ >= MAX_FINDINGS) {
                    break;
                }
                String clause = LlmUtils.sanitizeText(f.path("clause").asText(""), MAX_FINDING_TEXT_CHARS);
                String risk = normalizeRiskLevel(f.path("risk").asText(null));
                String explanation = LlmUtils.sanitizeText(f.path("explanation").asText(""), MAX_FINDING_TEXT_CHARS);
                String confidence = normalizeConfidence(f.path("confidence").asText(null));
                if (clause.isBlank() && explanation.isBlank()) {
                    continue;
                }
                findings.add(new Finding(
                        clause,
                        risk,
                        explanation,
                        confidence
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
                        LlmUtils.sanitizeText(rm.match().embedded().text(), 120)))
                .toList();
    }

    private String extractAnswer(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        int idx = response.lastIndexOf("Confidence:");
        String answer = idx > 0 ? response.substring(0, idx) : response;
        return LlmUtils.sanitizeText(answer, 10_000);
    }

    private String extractConfidence(String response) {
        String normalized = LlmUtils.sanitizeText(response, 10_000).toUpperCase(Locale.ROOT);
        if (normalized.contains("CONFIDENCE: HIGH")) return "HIGH";
        if (normalized.contains("CONFIDENCE: LOW")) return "LOW";
        return "MEDIUM";
    }

    private String normalizeRiskLevel(String value) {
        return LlmUtils.normalizeChoice(value, "MEDIUM", "HIGH", "MEDIUM", "LOW");
    }

    private String normalizeConfidence(String value) {
        return LlmUtils.normalizeChoice(value, "MEDIUM", "HIGH", "MEDIUM", "LOW");
    }

    private static String stripMarkdownFences(String text) { return LlmUtils.stripMarkdownFences(text); }

    private static long elapsedMs(long startMs) {
        return System.currentTimeMillis() - startMs;
    }
}

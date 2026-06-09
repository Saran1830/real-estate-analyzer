package com.compliance.agent.service;

import com.compliance.agent.util.LlmUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends run traces to LangSmith via its REST API.
 * All calls are fire-and-forget — failures are logged as warnings and never
 * propagate to callers. If LANGSMITH_API_KEY is not set, all calls are skipped.
 */
@Service
@Slf4j
public class LangSmithService {

    private final WebClient webClient;

    @Value("${langsmith.api.key:}")
    private String apiKey;

    @Value("${langsmith.project}")
    private String project;

    @Value("${langsmith.base.url}")
    private String baseUrl;

    public LangSmithService(WebClient webClient) {
        this.webClient = webClient;
    }

    public String startRun(String name, String runType, Map<String, Object> inputs, String parentRunId) {
        if (apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) return null;
        String runId = UUID.randomUUID().toString();
        try {
            Map<String, Object> body = buildRunBody(runId, name, runType, inputs, parentRunId);
            post("/runs", body);
        } catch (Exception e) {
            log.warn("LangSmith startRun failed: {}", LlmUtils.sanitizeForLog(e.getMessage(), 200));
        }
        return runId;
    }

    public void endRun(String runId, Map<String, Object> outputs, String error) {
        if (apiKey.isBlank() || runId == null || baseUrl == null || baseUrl.isBlank()) return;
        try {
            Map<String, Object> body;
            if (error != null) {
                body = Map.of("error", error, "end_time", Instant.now().toString());
            } else if (outputs != null) {
                body = Map.of("outputs", outputs, "end_time", Instant.now().toString());
            } else {
                body = Map.of("end_time", Instant.now().toString());
            }
            patch("/runs/" + runId, body);
        } catch (Exception e) {
            log.warn("LangSmith endRun failed: {}", LlmUtils.sanitizeForLog(e.getMessage(), 200));
        }
    }

    private void post(String path, @NonNull Map<String, Object> body) {
        webClient.post()
                .uri(baseUrl + path)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        r -> log.debug("LangSmith POST {}: ok", path),
                        e -> log.warn("LangSmith POST {} failed ({}): {}", path,
                                e.getClass().getSimpleName(), LlmUtils.sanitizeForLog(e.getMessage(), 200))
                );
    }

    private void patch(String path, @NonNull Map<String, Object> body) {
        webClient.patch()
                .uri(baseUrl + path)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        r -> log.debug("LangSmith PATCH {}: ok", path),
                        e -> log.warn("LangSmith PATCH {} failed ({}): {}", path,
                                e.getClass().getSimpleName(), LlmUtils.sanitizeForLog(e.getMessage(), 200))
                );
    }

    private Map<String, Object> buildRunBody(
            String runId, String name, String runType,
            Map<String, Object> inputs, String parentRunId) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", runId);
        body.put("name", name);
        body.put("run_type", runType);
        body.put("inputs", inputs);
        body.put("start_time", Instant.now().toString());
        body.put("project_name", project);
        if (parentRunId != null) body.put("parent_run_id", parentRunId);
        return body;
    }
}

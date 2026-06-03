package com.compliance.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sends run traces to LangSmith via its REST API.
 * If LANGSMITH_API_KEY is not set, all calls are silently skipped.
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
        if (apiKey.isBlank()) return null;
        String runId = UUID.randomUUID().toString();
        try {
            Map<String, Object> body = buildRunBody(runId, name, runType, inputs, parentRunId, null);
            post("/runs", body);
        } catch (Exception e) {
            log.warn("LangSmith startRun failed: {}", e.getMessage());
        }
        return runId;
    }

    public void endRun(String runId, Map<String, Object> outputs, String error) {
        if (apiKey.isBlank() || runId == null) return;
        try {
            Map<String, Object> body = error == null
                    ? Map.of("outputs", outputs, "end_time", Instant.now().toString())
                    : Map.of("error", error, "end_time", Instant.now().toString());
            patch("/runs/" + runId, body);
        } catch (Exception e) {
            log.warn("LangSmith endRun failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("null")
    private void post(String path, Map<String, Object> body) {
        webClient.post()
                .uri(baseUrl + path)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        r -> log.debug("LangSmith POST {}: {}", path, r),
                        e -> log.warn("LangSmith POST {} error: {}", path, e.getMessage())
                );
    }

    @SuppressWarnings("null")
    private void patch(String path, Map<String, Object> body) {
        webClient.patch()
                .uri(baseUrl + path)
                .header("x-api-key", apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        r -> log.debug("LangSmith PATCH {}: {}", path, r),
                        e -> log.warn("LangSmith PATCH {} error: {}", path, e.getMessage())
                );
    }

    private Map<String, Object> buildRunBody(
            String runId, String name, String runType,
            Map<String, Object> inputs, String parentRunId, String sessionId) {
        var builder = new java.util.HashMap<String, Object>();
        builder.put("id", runId);
        builder.put("name", name);
        builder.put("run_type", runType);
        builder.put("inputs", inputs);
        builder.put("start_time", Instant.now().toString());
        builder.put("project_name", project);
        if (parentRunId != null) builder.put("parent_run_id", parentRunId);
        if (sessionId != null) builder.put("session_id", sessionId);
        return builder;
    }
}

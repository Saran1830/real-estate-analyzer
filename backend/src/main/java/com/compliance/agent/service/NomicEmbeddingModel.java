package com.compliance.agent.service;

import com.compliance.agent.util.LlmUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

public class NomicEmbeddingModel implements ComplianceEmbeddingModel {

    private static final String QUERY_TASK_TYPE = "search_query";
    private static final String DOCUMENT_TASK_TYPE = "search_document";

    private final RestClient restClient;
    private final String apiKey;
    private final String modelName;
    private final String embeddingsUrl;

    public NomicEmbeddingModel(RestClient restClient, String apiKey, String baseUrl, String modelName) {
        this.restClient = restClient;
        this.apiKey = requireValue(apiKey, "embedding.api.key");
        this.modelName = requireValue(modelName, "embedding.model");
        this.embeddingsUrl = normalizeEmbeddingsUrl(baseUrl);
    }

    @Override
    public Embedding embed(String text) {
        String safeText = requireText(text, "text");
        return embedTexts(List.of(safeText), QUERY_TASK_TYPE).get(0);
    }

    @Override
    public List<Embedding> embedAll(List<TextSegment> textSegments) {
        if (textSegments == null || textSegments.isEmpty()) {
            throw new IllegalArgumentException("textSegments must not be empty");
        }

        List<String> texts = textSegments.stream()
                .map(TextSegment::text)
                .map(text -> requireText(text, "textSegment.text"))
                .toList();
        return embedTexts(texts, DOCUMENT_TASK_TYPE);
    }

    private List<Embedding> embedTexts(List<String> texts, String taskType) {
        try {
            NomicEmbeddingResponse response = restClient.post()
                    .uri(embeddingsUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new NomicEmbeddingRequest(texts, modelName, taskType))
                    .retrieve()
                    .body(NomicEmbeddingResponse.class);

            if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
                throw new IllegalStateException("Nomic embedding response did not contain embeddings");
            }
            if (response.embeddings().size() != texts.size()) {
                throw new IllegalStateException("Nomic embedding response size did not match request size");
            }

            return response.embeddings().stream()
                    .map(NomicEmbeddingModel::toEmbedding)
                    .toList();
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Nomic embedding request failed with HTTP "
                    + e.getStatusCode().value() + " " + e.getStatusText(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Nomic embedding request failed: "
                    + LlmUtils.sanitizeForLog(e.getMessage(), 200), e);
        }
    }

    private static Embedding toEmbedding(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalStateException("Nomic embedding vector must not be empty");
        }

        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Double value = vector.get(i);
            values[i] = value == null ? 0.0f : value.floatValue();
        }
        return Embedding.from(values);
    }

    private static String normalizeEmbeddingsUrl(String baseUrl) {
        String safeBaseUrl = requireValue(baseUrl, "embedding.base-url").trim();
        if (safeBaseUrl.endsWith("/embedding/text")) {
            return safeBaseUrl;
        }
        while (safeBaseUrl.endsWith("/")) {
            safeBaseUrl = safeBaseUrl.substring(0, safeBaseUrl.length() - 1);
        }
        return safeBaseUrl + "/embedding/text";
    }

    private static String requireValue(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private record NomicEmbeddingRequest(
            List<String> texts,
            String model,
            @JsonProperty("task_type") String taskType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NomicEmbeddingResponse(List<List<Double>> embeddings) {}
}

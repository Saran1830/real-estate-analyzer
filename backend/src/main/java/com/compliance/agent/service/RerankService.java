package com.compliance.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class RerankService {

    private final RestClient restClient;

    @Value("${cohere.api.key:}")
    private String cohereApiKey;

    @Value("${cohere.model}")
    private String cohereModel;

    @Value("${cohere.base.url}")
    private String cohereBaseUrl;

    @Value("${rag.rerank.enabled}")
    private boolean rerankEnabled;

    public RerankService(@Qualifier("rerankRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public List<RankedMatch> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topN) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }
        if (candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        int requestedTopN = Math.min(topN, candidates.size());
        boolean baseUrlConfigured = cohereBaseUrl != null && !cohereBaseUrl.isBlank();

        if (!rerankEnabled || cohereApiKey.isBlank() || cohereModel == null || cohereModel.isBlank() || !baseUrlConfigured) {
            log.debug("Rerank skipped (enabled={}, keySet={}, modelSet={}, baseUrlSet={}); using top-N cosine",
                    rerankEnabled, !cohereApiKey.isBlank(), cohereModel != null && !cohereModel.isBlank(), baseUrlConfigured);
            return toRankedFallback(candidates, requestedTopN);
        }

        List<String> docs = candidates.stream()
                .map(m -> com.compliance.agent.util.LlmUtils.sanitizeText(m.embedded().text(), 4_000))
                .toList();

        CohereRequest cohereRequest = new CohereRequest(cohereModel, query, docs, requestedTopN);

        try {
            CohereRerankResponse response = restClient.post()
                    .uri(cohereBaseUrl)
                    .header("Authorization", "Bearer " + cohereApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cohereRequest)
                    .retrieve()
                    .body(CohereRerankResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Empty Cohere response; falling back to cosine ranking");
                return toRankedFallback(candidates, requestedTopN);
            }

            List<RankedMatch> ranked = new ArrayList<>();
            for (CohereResult result : response.results()) {
                if (result.index() < 0 || result.index() >= candidates.size()) {
                    log.warn("Cohere returned out-of-bounds index {} for {} candidates; skipping",
                            result.index(), candidates.size());
                    continue;
                }
                EmbeddingMatch<TextSegment> original = candidates.get(result.index());
                ranked.add(new RankedMatch(original, result.index(), original.score(), result.relevanceScore()));
            }
            ranked.sort(Comparator.comparingDouble(RankedMatch::rerankScore).reversed());
            log.debug("Reranked {} candidates to top-{}", candidates.size(), requestedTopN);
            return ranked.stream().limit(requestedTopN).toList();

        } catch (RestClientResponseException e) {
            log.warn("Cohere rerank HTTP {} {}: falling back to cosine ranking",
                    e.getStatusCode().value(), e.getStatusText());
            return toRankedFallback(candidates, requestedTopN);
        } catch (Exception e) {
            log.warn("Cohere rerank failed ({}): {}; falling back to cosine ranking",
                    e.getClass().getSimpleName(), com.compliance.agent.util.LlmUtils.sanitizeForLog(e.getMessage(), 200));
            return toRankedFallback(candidates, requestedTopN);
        }
    }

    private List<RankedMatch> toRankedFallback(List<EmbeddingMatch<TextSegment>> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        int limit = Math.min(topN, candidates.size());
        List<RankedMatch> ranked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            EmbeddingMatch<TextSegment> match = candidates.get(i);
            ranked.add(new RankedMatch(match, i, match.score(), match.score()));
        }
        return ranked;
    }

    public record RankedMatch(
            EmbeddingMatch<TextSegment> match,
            int originalIndex,
            double cosineScore,
            double rerankScore
    ) {}

    private record CohereRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CohereRerankResponse(List<CohereResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CohereResult(int index, double relevanceScore) {
        CohereResult {
            // Jackson uses snake_case via @JsonProperty; handled below
        }

        @com.fasterxml.jackson.annotation.JsonCreator
        static CohereResult fromJson(
                @com.fasterxml.jackson.annotation.JsonProperty("index") int index,
                @com.fasterxml.jackson.annotation.JsonProperty("relevance_score") double relevanceScore
        ) {
            return new CohereResult(index, relevanceScore);
        }
    }
}

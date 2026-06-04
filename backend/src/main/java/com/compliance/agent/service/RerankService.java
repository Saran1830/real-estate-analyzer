package com.compliance.agent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class RerankService {

    private final WebClient webClient;

    @Value("${cohere.api.key:}")
    private String cohereApiKey;

    @Value("${cohere.model}")
    private String cohereModel;

    @Value("${cohere.base.url}")
    private String cohereBaseUrl;

    @Value("${rag.rerank.enabled}")
    private boolean rerankEnabled;

    public RerankService(WebClient webClient) {
        this.webClient = webClient;
    }

    public List<RankedMatch> rerank(String query, List<EmbeddingMatch<TextSegment>> candidates, int topN) {
        if (!rerankEnabled || cohereApiKey.isBlank()) {
            log.debug("Rerank skipped (enabled={}, keySet={}); using top-N cosine", rerankEnabled, !cohereApiKey.isBlank());
            return toRankedFallback(candidates, topN);
        }

        List<String> docs = candidates.stream()
                .map(m -> m.embedded().text())
                .toList();

        CohereRequest cohereRequest = new CohereRequest(cohereModel, query, docs, topN);

        try {
            CohereRerankResponse response = webClient.post()
                    .uri(Objects.requireNonNull(cohereBaseUrl))
                    .header("Authorization", "Bearer " + cohereApiKey)
                    .header("Content-Type", "application/json")
                    .body(BodyInserters.fromValue(cohereRequest))
                    .retrieve()
                    .bodyToMono(CohereRerankResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Empty Cohere response; falling back to cosine ranking");
                return toRankedFallback(candidates, topN);
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
            log.debug("Reranked {} candidates to top-{}", candidates.size(), topN);
            return ranked;

        } catch (WebClientResponseException e) {
            log.warn("Cohere rerank HTTP {} {}: falling back to cosine ranking",
                    e.getStatusCode().value(), e.getStatusText());
            return toRankedFallback(candidates, topN);
        } catch (Exception e) {
            log.warn("Cohere rerank failed ({}): {}; falling back to cosine ranking",
                    e.getClass().getSimpleName(), e.getMessage());
            return toRankedFallback(candidates, topN);
        }
    }

    private List<RankedMatch> toRankedFallback(List<EmbeddingMatch<TextSegment>> candidates, int topN) {
        return candidates.stream()
                .limit(topN)
                .map(m -> new RankedMatch(m, candidates.indexOf(m), m.score(), m.score()))
                .toList();
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

package com.compliance.agent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock
    private WebClient webClient;

    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        rerankService = new RerankService(webClient);
        RerankService svc = Objects.requireNonNull(rerankService);
        ReflectionTestUtils.setField(svc, "cohereApiKey", "");
        ReflectionTestUtils.setField(svc, "cohereModel", "rerank-english-v3.0");
        ReflectionTestUtils.setField(svc, "cohereBaseUrl", "https://api.cohere.com/v1/rerank");
        ReflectionTestUtils.setField(svc, "rerankEnabled", true);
    }

    private static EmbeddingMatch<TextSegment> match(String text, double score) {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        Embedding embedding = Embedding.from(vector);
        return new EmbeddingMatch<>(score, "id-" + text.hashCode(),
                embedding, TextSegment.from(text));
    }

    @Test
    void blankApiKeyFallsBackToCosineTopN() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("Chunk A", 0.90),
                match("Chunk B", 0.85),
                match("Chunk C", 0.80),
                match("Chunk D", 0.75),
                match("Chunk E", 0.70),
                match("Chunk F", 0.65)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("query", candidates, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).match().embedded().text()).isEqualTo("Chunk A");
        assertThat(result.get(1).match().embedded().text()).isEqualTo("Chunk B");
        assertThat(result.get(2).match().embedded().text()).isEqualTo("Chunk C");
    }

    @Test
    void blankApiKeyPreservesOriginalScoresInFallback() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("Chunk A", 0.95),
                match("Chunk B", 0.80)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("query", candidates, 2);

        assertThat(result.get(0).cosineScore()).isEqualTo(0.95);
        assertThat(result.get(0).rerankScore()).isEqualTo(0.95);
    }

    @Test
    void rerankDisabledFallsBackToCosine() {
        ReflectionTestUtils.setField(Objects.requireNonNull(rerankService), "rerankEnabled", false);
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("A", 0.9),
                match("B", 0.8)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("q", candidates, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).match().embedded().text()).isEqualTo("A");
    }

    @Test
    void topNRespectedInFallback() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("1", 0.9), match("2", 0.8), match("3", 0.7),
                match("4", 0.6), match("5", 0.5)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("q", candidates, 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void emptyInputReturnsEmptyList() {
        List<RerankService.RankedMatch> result = rerankService.rerank("q", List.of(), 5);
        assertThat(result).isEmpty();
    }

    @Test
    void topNLargerThanCandidatesReturnsAllCandidates() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("A", 0.9),
                match("B", 0.8)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("q", candidates, 10);

        assertThat(result).hasSize(2);
    }
}

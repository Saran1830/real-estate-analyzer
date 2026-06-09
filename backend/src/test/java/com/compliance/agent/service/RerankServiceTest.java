package com.compliance.agent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
class RerankServiceTest {

    private static final String COHERE_URL = "https://api.cohere.com/v1/rerank";

    private MockRestServiceServer server;
    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        rerankService = new RerankService(builder.build());

        ReflectionTestUtils.setField(rerankService, "cohereApiKey", "");
        ReflectionTestUtils.setField(rerankService, "cohereModel", "rerank-english-v3.0");
        ReflectionTestUtils.setField(rerankService, "cohereBaseUrl", COHERE_URL);
        ReflectionTestUtils.setField(rerankService, "rerankEnabled", true);
    }

    private static void enableCohereCall(RerankService service) {
        ReflectionTestUtils.setField(service, "cohereApiKey", "test-api-key");
        ReflectionTestUtils.setField(service, "cohereModel", "rerank-english-v3.0");
        ReflectionTestUtils.setField(service, "cohereBaseUrl", COHERE_URL);
        ReflectionTestUtils.setField(service, "rerankEnabled", true);
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
        ReflectionTestUtils.setField(rerankService, "rerankEnabled", false);
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("A", 0.9),
                match("B", 0.8)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("q", candidates, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).match().embedded().text()).isEqualTo("A");
    }

    @Test
    void cohereSuccessReordersByRerankScore() {
        enableCohereCall(rerankService);
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("Chunk A", 0.90),
                match("Chunk B", 0.85)
        );

        server.expect(requestTo(COHERE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {
                          "results": [
                            {"index": 1, "relevance_score": 0.98},
                            {"index": 0, "relevance_score": 0.12}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<RerankService.RankedMatch> result = rerankService.rerank("query", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).match().embedded().text()).isEqualTo("Chunk B");
        assertThat(result.get(1).match().embedded().text()).isEqualTo("Chunk A");
        assertThat(result.get(0).rerankScore()).isEqualTo(0.98);
        assertThat(result.get(1).rerankScore()).isEqualTo(0.12);
        server.verify();
    }

    @Test
    void cohereHttpErrorFallsBackToCosine() {
        enableCohereCall(rerankService);
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("Chunk A", 0.90),
                match("Chunk B", 0.85)
        );

        server.expect(requestTo(COHERE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        List<RerankService.RankedMatch> result = rerankService.rerank("query", candidates, 2);

        assertThat(result).extracting(match -> match.match().embedded().text())
                .containsExactly("Chunk A", "Chunk B");
        server.verify();
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
    void nonPositiveTopNReturnsEmptyList() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(
                match("A", 0.9),
                match("B", 0.8)
        );

        List<RerankService.RankedMatch> result = rerankService.rerank("q", candidates, 0);

        assertThat(result).isEmpty();
    }

    @Test
    void blankQueryIsRejected() {
        List<EmbeddingMatch<TextSegment>> candidates = List.of(match("A", 0.9));

        assertThatThrownBy(() -> rerankService.rerank("  ", candidates, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query must not be blank");
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

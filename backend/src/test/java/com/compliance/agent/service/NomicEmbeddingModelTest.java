package com.compliance.agent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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

class NomicEmbeddingModelTest {

    private static final String BASE_URL = "https://api-atlas.nomic.ai/v1";
    private static final String EMBEDDING_URL = BASE_URL + "/embedding/text";

    private MockRestServiceServer server;
    private NomicEmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        embeddingModel = new NomicEmbeddingModel(
                builder.build(),
                "test-api-key",
                BASE_URL,
                "nomic-embed-text-v1.5"
        );
    }

    @Test
    void embedUsesQueryEndpointContract() {
        server.expect(requestTo(EMBEDDING_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "texts": ["What is the closing date?"],
                          "model": "nomic-embed-text-v1.5",
                          "task_type": "search_query"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "embeddings": [[0.1, 0.2, 0.3]]
                        }
                        """, MediaType.APPLICATION_JSON));

        Embedding embedding = embeddingModel.embed("What is the closing date?");

        assertThat(embedding.vector()).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }

    @Test
    void embedAllUsesDocumentTaskType() {
        server.expect(requestTo(EMBEDDING_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "texts": ["Clause A", "Clause B"],
                          "model": "nomic-embed-text-v1.5",
                          "task_type": "search_document"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "embeddings": [[0.1, 0.2], [0.3, 0.4]]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Embedding> embeddings = embeddingModel.embedAll(List.of(
                TextSegment.from("Clause A"),
                TextSegment.from("Clause B")
        ));

        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0).vector()).containsExactly(0.1f, 0.2f);
        assertThat(embeddings.get(1).vector()).containsExactly(0.3f, 0.4f);
        server.verify();
    }

    @Test
    void blankBaseUrlIsRejected() {
        RestClient client = RestClient.builder().build();

        assertThatThrownBy(() -> new NomicEmbeddingModel(client, "key", " ", "model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding.base-url");
    }

    @Test
    void httpErrorsAreWrapped() {
        server.expect(requestTo(EMBEDDING_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        assertThatThrownBy(() -> embeddingModel.embed("question"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");

        server.verify();
    }
}

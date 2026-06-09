package com.compliance.agent.config;

import com.compliance.agent.service.ComplianceEmbeddingModel;
import com.compliance.agent.service.NomicEmbeddingModel;
import com.compliance.agent.service.OpenAiEmbeddingAdapter;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    @Value("${llm.api.key}")
    private String llmApiKey;

    @Value("${llm.base-url:}")
    private String llmBaseUrl;

    @Value("${llm.model}")
    private String llmModel;

    @Value("${llm.temperature}")
    private double llmTemperature;

    @Value("${embedding.api.key}")
    private String embeddingApiKey;

    @Value("${embedding.base-url:}")
    private String embeddingBaseUrl;

    @Value("${embedding.model}")
    private String embeddingModel;

    @Bean
    public OpenAiChatModel openAiChatModel() {
        var builder = OpenAiChatModel.builder()
                .apiKey(llmApiKey)
                .modelName(llmModel)
                .temperature(llmTemperature)
                .timeout(Duration.ofSeconds(60));
        if (!llmBaseUrl.isBlank()) {
            builder.baseUrl(llmBaseUrl);
        }
        return builder.build();
    }

    @Bean
    public ComplianceEmbeddingModel complianceEmbeddingModel(
            @Qualifier("embeddingRestClient") RestClient embeddingRestClient) {
        if (!embeddingBaseUrl.isBlank()) {
            return new NomicEmbeddingModel(embeddingRestClient, embeddingApiKey, embeddingBaseUrl, embeddingModel);
        }

        OpenAiEmbeddingModel delegate = OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(30))
                .build();
        return new OpenAiEmbeddingAdapter(delegate);
    }
}

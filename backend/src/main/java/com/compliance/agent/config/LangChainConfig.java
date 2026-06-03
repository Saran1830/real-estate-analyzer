package com.compliance.agent.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public OpenAiEmbeddingModel openAiEmbeddingModel() {
        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(30));
        if (!embeddingBaseUrl.isBlank()) {
            builder.baseUrl(embeddingBaseUrl);
        }
        return builder.build();
    }
}

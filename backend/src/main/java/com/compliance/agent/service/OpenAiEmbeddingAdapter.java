package com.compliance.agent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.List;

public class OpenAiEmbeddingAdapter implements ComplianceEmbeddingModel {

    private final OpenAiEmbeddingModel delegate;

    public OpenAiEmbeddingAdapter(OpenAiEmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public Embedding embed(String text) {
        return delegate.embed(text).content();
    }

    @Override
    public List<Embedding> embedAll(List<TextSegment> textSegments) {
        return delegate.embedAll(textSegments).content();
    }
}

package com.compliance.agent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

public interface ComplianceEmbeddingModel {

    Embedding embed(String text);

    List<Embedding> embedAll(List<TextSegment> textSegments);
}

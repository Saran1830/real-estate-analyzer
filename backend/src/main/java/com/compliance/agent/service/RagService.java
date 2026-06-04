package com.compliance.agent.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final OpenAiEmbeddingModel embeddingModel;

    @Value("${chroma.base.url}")
    private String chromaBaseUrl;

    @Value("${rag.retrieval.top-k}")
    private int topK;

    @Value("${rag.chunk.size}")
    private int chunkSize;

    @Value("${rag.chunk.overlap}")
    private int chunkOverlap;

    private static final long SESSION_TTL_HOURS = 1;

    private final Map<String, ChromaEmbeddingStore> storeCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAccessed = new ConcurrentHashMap<>();

    public void ingestDocument(String sessionId, String documentText) {
        log.info("Ingesting document for session={}", sessionId);
        touch(sessionId);
        ChromaEmbeddingStore store = getOrCreateStore(sessionId);

        Document document = Document.from(documentText);
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> segments = splitter.split(document);

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);

        log.info("Ingested {} chunks for session={}", segments.size(), sessionId);
    }

    public List<EmbeddingMatch<TextSegment>> retrieve(String sessionId, String query) {
        touch(sessionId);
        ChromaEmbeddingStore store = getOrCreateStore(sessionId);
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();

        List<EmbeddingMatch<TextSegment>> matches = store.search(request).matches();
        log.debug("Retrieved {} candidates for session={} query='{}'", matches.size(), sessionId, query);
        return matches;
    }

    public void ingestDocuments(String sessionId, List<com.compliance.agent.model.DealModels.DealDocument> documents) {
        for (com.compliance.agent.model.DealModels.DealDocument doc : documents) {
            ingestDocument(sessionId, "=== " + doc.name() + " ===\n" + doc.text());
        }
    }

    public void deleteSession(String sessionId) {
        lastAccessed.remove(sessionId);
        ChromaEmbeddingStore store = storeCache.remove(sessionId);
        if (store != null) {
            try {
                store.removeAll();
                log.info("Deleted ChromaDB collection for session={}", sessionId);
            } catch (Exception e) {
                log.warn("Failed to delete ChromaDB collection for session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    @Scheduled(fixedDelay = 600_000) // every 10 minutes
    public void evictExpiredSessions() {
        try {
            Instant cutoff = Instant.now().minus(SESSION_TTL_HOURS, ChronoUnit.HOURS);
            lastAccessed.entrySet().removeIf(entry -> {
                if (entry.getValue().isBefore(cutoff)) {
                    ChromaEmbeddingStore store = storeCache.remove(entry.getKey());
                    if (store != null) {
                        try {
                            store.removeAll();
                        } catch (Exception e) {
                            log.warn("ChromaDB cleanup failed for session={}: {}", entry.getKey(), e.getMessage());
                        }
                    }
                    log.debug("Evicted expired RAG session={}", entry.getKey());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.error("RAG session eviction task failed — will retry on next tick: {}", e.getMessage(), e);
        }
    }

    private void touch(String sessionId) {
        lastAccessed.put(sessionId, Instant.now());
    }

    private ChromaEmbeddingStore getOrCreateStore(String sessionId) {
        return storeCache.computeIfAbsent(sessionId, id ->
                ChromaEmbeddingStore.builder()
                        .baseUrl(chromaBaseUrl)
                        .collectionName("compliance_" + id.replace("-", "_"))
                        .build()
        );
    }
}

package com.compliance.agent.service;

import com.compliance.agent.util.LlmUtils;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final ComplianceEmbeddingModel embeddingModel;

    @Value("${chroma.base.url}")
    private String chromaBaseUrl;

    @Value("${rag.retrieval.top-k}")
    private int topK;

    @Value("${rag.parent.chunk.size:600}")
    private int parentChunkSize;

    @Value("${rag.child.chunk.size:150}")
    private int childChunkSize;

    private static final long SESSION_TTL_HOURS = 1;

    private final Map<String, ChromaEmbeddingStore> storeCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAccessed = new ConcurrentHashMap<>();
    // sessionId → (parentId → parentText)
    private final Map<String, Map<String, String>> parentStore = new ConcurrentHashMap<>();

    public void ingestDocument(String sessionId, String documentText) {
        String safeSessionId = requireSessionId(sessionId);
        String safeDocumentText = requireDocumentText(documentText);
        log.info("Ingesting document for session={}", LlmUtils.safeIdentifier(safeSessionId, 32));
        touch(safeSessionId);
        ChromaEmbeddingStore store = getOrCreateStore(safeSessionId);

        // Split document into large parent chunks
        DocumentSplitter parentSplitter = DocumentSplitters.recursive(parentChunkSize, parentChunkSize / 10);
        List<TextSegment> parentSegments = parentSplitter.split(Document.from(safeDocumentText));

        // Split each parent into small child chunks; tag each child with its parent_id
        String sessionPrefix = LlmUtils.sha256Hex(safeSessionId).substring(0, 8);
        Map<String, String> sessionParents = parentStore.computeIfAbsent(safeSessionId, k -> new ConcurrentHashMap<>());
        List<TextSegment> childSegments = new ArrayList<>();

        DocumentSplitter childSplitter = DocumentSplitters.recursive(childChunkSize, childChunkSize / 5);
        for (int i = 0; i < parentSegments.size(); i++) {
            String parentId = sessionPrefix + "_p" + i;
            String parentText = parentSegments.get(i).text();
            sessionParents.put(parentId, parentText);

            for (TextSegment child : childSplitter.split(Document.from(parentText))) {
                childSegments.add(TextSegment.from(child.text(), Metadata.from("parent_id", parentId)));
            }
        }

        List<Embedding> embeddings = embeddingModel.embedAll(childSegments);
        store.addAll(embeddings, childSegments);

        log.info("Ingested {} parent chunks → {} child chunks for session={}",
                parentSegments.size(), childSegments.size(), LlmUtils.safeIdentifier(safeSessionId, 32));
    }

    public List<EmbeddingMatch<TextSegment>> retrieve(String sessionId, String query) {
        String safeSessionId = requireSessionId(sessionId);
        String safeQuery = requireQuery(query);
        touch(safeSessionId);
        ChromaEmbeddingStore store = getOrCreateStore(safeSessionId);
        Embedding queryEmbedding = embeddingModel.embed(safeQuery);

        // Retrieve top-k child chunks by cosine similarity
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .build();
        List<EmbeddingMatch<TextSegment>> childMatches = store.search(request).matches();

        // Swap child text for the full parent chunk, deduplicating by parent_id
        Map<String, String> sessionParents = parentStore.getOrDefault(safeSessionId, Map.of());
        List<EmbeddingMatch<TextSegment>> parentMatches = new ArrayList<>();
        Set<String> seenParentIds = new LinkedHashSet<>();

        for (EmbeddingMatch<TextSegment> child : childMatches) {
            String parentId = child.embedded().metadata().getString("parent_id");
            if (parentId != null && seenParentIds.add(parentId)) {
                String parentText = sessionParents.get(parentId);
                if (parentText != null) {
                    parentMatches.add(new EmbeddingMatch<>(
                            child.score(),
                            child.embeddingId(),
                            child.embedding(),
                            TextSegment.from(parentText, child.embedded().metadata())
                    ));
                }
            }
        }

        log.debug("Retrieved {} parent chunks (from {} child candidates) for session={} queryLength={}",
                parentMatches.size(), childMatches.size(),
                LlmUtils.safeIdentifier(safeSessionId, 32), safeQuery.length());
        return parentMatches;
    }

    public void ingestDocuments(String sessionId, List<com.compliance.agent.model.DealModels.DealDocument> documents) {
        for (com.compliance.agent.model.DealModels.DealDocument doc : documents) {
            ingestDocument(sessionId, "=== " + doc.name() + " ===\n" + doc.text());
        }
    }

    public void deleteSession(String sessionId) {
        String safeSessionId = requireSessionId(sessionId);
        lastAccessed.remove(safeSessionId);
        parentStore.remove(safeSessionId);
        ChromaEmbeddingStore store = storeCache.remove(safeSessionId);
        if (store != null) {
            try {
                store.removeAll();
                log.info("Deleted ChromaDB collection for session={}", LlmUtils.safeIdentifier(safeSessionId, 32));
            } catch (Exception e) {
                log.warn("Failed to delete ChromaDB collection for session={}: {}",
                        LlmUtils.safeIdentifier(safeSessionId, 32), LlmUtils.sanitizeForLog(e.getMessage(), 200));
            }
        }
    }

    @Scheduled(fixedDelay = 600_000) // every 10 minutes
    public void evictExpiredSessions() {
        try {
            Instant cutoff = Instant.now().minus(SESSION_TTL_HOURS, ChronoUnit.HOURS);
            lastAccessed.entrySet().removeIf(entry -> {
                if (entry.getValue().isBefore(cutoff)) {
                    parentStore.remove(entry.getKey());
                    ChromaEmbeddingStore store = storeCache.remove(entry.getKey());
                    if (store != null) {
                        try {
                            store.removeAll();
                        } catch (Exception e) {
                            log.warn("ChromaDB cleanup failed for session={}: {}",
                                    LlmUtils.safeIdentifier(entry.getKey(), 32),
                                    LlmUtils.sanitizeForLog(e.getMessage(), 200));
                        }
                    }
                    log.debug("Evicted expired RAG session={}",
                            LlmUtils.safeIdentifier(entry.getKey(), 32));
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.error("RAG session eviction task failed - will retry on next tick: {}",
                    LlmUtils.sanitizeForLog(e.getMessage(), 200), e);
        }
    }

    private void touch(String sessionId) {
        lastAccessed.put(sessionId, Instant.now());
    }

    private ChromaEmbeddingStore getOrCreateStore(String sessionId) {
        return storeCache.computeIfAbsent(sessionId, id ->
                ChromaEmbeddingStore.builder()
                        .baseUrl(chromaBaseUrl)
                        .collectionName(collectionNameForSession(id))
                        .build()
        );
    }

    static String collectionNameForSession(String sessionId) {
        return "compliance_" + LlmUtils.sha256Hex(requireSessionId(sessionId)).substring(0, 24);
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private static String requireDocumentText(String documentText) {
        if (documentText == null || documentText.isBlank()) {
            throw new IllegalArgumentException("documentText must not be blank");
        }
        return documentText;
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return query;
    }
}

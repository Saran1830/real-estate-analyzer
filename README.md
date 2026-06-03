# AI Compliance Document Review Agent

Multi-tenant real estate compliance review powered by a LangGraph-style state machine, two-stage RAG retrieval, and conversation memory.

**Stack:** Java 21 · Spring Boot 3 · LangChain4j · ChromaDB · Cohere · OpenAI GPT-4o · LangSmith · Next.js 14

---

## RAGAS Evaluation Results

| Metric | Baseline (cosine only) | Re-ranked (Cohere) | Delta |
|--------|----------------------|-------------------|-------|
| Faithfulness | 0.76 | **0.85** | +0.09 |
| Answer Relevancy | 0.81 | **0.83** | +0.02 |

The 9-point faithfulness improvement comes from Cohere's cross-encoder model surfacing penalty and default clauses that cosine similarity was ranking below more topically broad chunks.

---

## Architecture

```
User query
    │
    ▼
[Guardrail Node] — blocks off-topic requests
    │
    ▼
[Ingest Node] — chunk → embed → ChromaDB
    │
    ▼
[Analyze Node] — GPT-4o with real estate domain prompt
    │
    ▼
[Q&A Node] — top-20 cosine → Cohere re-rank to top-5
              → MessageWindowChatMemory (last 10 turns)
              → GPT-4o with {history} + {context}
```

### Two-stage retrieval

```
Query → Embed → ChromaDB top-20 cosine similarity
                          │
                          ▼
             Cohere Rerank API (cross-encoder)
                          │
                          ▼
              Top-5 re-scored by actual relevance
                          │
                          ▼
             GPT-4o + conversation history → Answer
```

---

## Quick Start

### Prerequisites
- Java 21, Maven
- Node 20
- Docker Desktop
- API keys: OpenAI, Cohere (free tier), LangSmith

### 1. Start ChromaDB
```bash
docker run -p 8000:8000 chromadb/chroma
```

### 2. Set environment variables
```bash
cp .env.example .env
# Edit .env with your API keys
export OPENAI_API_KEY=...
export COHERE_API_KEY=...
export LANGSMITH_API_KEY=...
```

### 3. Run backend
```bash
cd backend
mvn spring-boot:run
# Backend starts on http://localhost:8080
```

### 4. Run frontend
```bash
cd frontend
npm install
npm run dev
# Frontend starts on http://localhost:3000
```

### Or: Docker Compose (all services)
```bash
docker compose up --build
```

---

## API Reference

### POST /api/compliance/analyze
Ingest and analyze a document. Returns a `sessionId` for follow-up Q&A.

```json
{
  "documentText": "WHOLESALE PURCHASE AND SALE AGREEMENT...",
  "documentType": "wholesale_purchase_agreement"
}
```

Response:
```json
{
  "sessionId": "abc123",
  "riskLevel": "HIGH",
  "summary": "...",
  "findings": [...],
  "agentTrace": [...]
}
```

### POST /api/compliance/ask
Ask a follow-up question. Maintains conversation memory across calls.

```json
{
  "sessionId": "abc123",
  "question": "What happens if the buyer defaults?"
}
```

### DELETE /api/compliance/session/{sessionId}
Clear conversation memory for a session.

---

## Supported Document Types

| Type | Prompt Used |
|------|-------------|
| wholesale_purchase_agreement | REAL_ESTATE_ANALYZE_PROMPT |
| loan_agreement | REAL_ESTATE_ANALYZE_PROMPT |
| letter_of_intent | REAL_ESTATE_ANALYZE_PROMPT |
| commercial_sales_agreement | REAL_ESTATE_ANALYZE_PROMPT |
| residential_lease | REAL_ESTATE_ANALYZE_PROMPT |
| design_construction_agreement | ANALYZE_PROMPT |
| vendor_contract | ANALYZE_PROMPT |
| nda | ANALYZE_PROMPT |

---

## Running RAGAS Evals

```bash
cd evals
pip install -r requirements.txt

# Baseline (set rag.rerank.enabled=false first)
python eval_pipeline.py --mode baseline

# Re-ranked (set rag.rerank.enabled=true)
python eval_pipeline.py --mode reranked

# Compare both runs
python eval_pipeline.py --mode compare
```

---

## Architecture Decision Records

### ADR-008: Cohere Re-ranker After Cosine Retrieval
**Why:** Cosine similarity retrieves topically related chunks, not necessarily the most useful ones for a specific query. LangSmith traces showed chunks about general closing procedures being ranked above penalty clauses for queries specifically about default consequences. Cohere's cross-encoder model reads query and document together, catching these semantic mismatches.

**Result:** Faithfulness 0.76 → 0.85 on the RAGAS eval suite.

**Trade-off:** ~200-400ms additional latency per Q&A request. Mitigated by graceful fallback to cosine results if Cohere is unavailable.

### ADR-009: MessageWindowChatMemory for Multi-turn Q&A
**Why:** Compliance review is inherently multi-turn. Without memory, users must re-state context on every question — defeating the purpose of a conversational interface. MessageWindowChatMemory from LangChain4j provides bounded, per-session history with no infrastructure requirement.

**Trade-off:** In-memory storage is lost on server restart. Production upgrade: persist to Redis with 24-hour TTL.

---

## Resume Bullets

- Built a multi-tenant real estate compliance agent using a LangGraph-style state machine — four named nodes (guardrail, ingest, analyze, Q&A) with AgentState flowing through a conditional orchestrator
- Implemented two-stage RAG retrieval: top-20 cosine similarity retrieval followed by Cohere re-ranking to top-5 — measured 9-point improvement in RAGAS faithfulness (0.76 → 0.85)
- Added multi-turn conversation memory using LangChain4j MessageWindowChatMemory — maintains last 10 turns per session, injected into prompts via {history} placeholder
- Instrumented full pipeline with LangSmith observability — traced cosine scores, re-rank scores, conversation history, and token cost per run

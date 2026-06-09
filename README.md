# AI Real Estate Agent

**Contract compliance review + deal underwriting powered by a two-stage RAG pipeline, multi-node LLM orchestration, and live market search.**

Built as a portfolio project demonstrating production AI engineering: retrieval-augmented generation, cross-encoder re-ranking, provider-agnostic LLM routing, and agent observability — all on a $0/month free-tier stack.

---

## Features

### Compliance Analyzer
Upload a real estate contract → the agent flags risky clauses, assigns risk levels (HIGH / MEDIUM / LOW), and opens a multi-turn Q&A session backed by conversation memory.

### Deal Analyzer
Upload multiple documents (purchase agreement, inspection report, comps, market data) → the agent infers the investment strategy, applies the right financial framework (70% Rule, Cap Rate, or Cash-on-Cash), and returns a scored verdict: **Strong Buy / Buy / Marginal / Pass** — with financials, risk factors, contract flags, and a specific recommendation.

---

## AI Architecture

### Agent State Machine

Both analyzers run a LangGraph-style state machine where `AgentState` flows through named nodes. Each node appends a `NodeExecution` to the trace, giving the UI a live audit trail.

```
 Compliance Analyzer              Deal Analyzer
 ──────────────────               ─────────────
 [Guardrail]                      [Web Search]  ← optional Tavily
      │                                │
 [Ingest]  ← embed + ChromaDB    [Ingest]       ← all docs, one session
      │                                │
 [Analyze] ← LLM + domain prompt [Analyze]      ← framework-aware prompt
      │                                │
 [Q&A]     ← RAG + memory        [Q&A]          ← same RAG stack
```

### Two-Stage RAG Retrieval

Simple cosine similarity retrieves *topically related* chunks — not necessarily the most *useful* ones. Cohere's cross-encoder re-ranker reads the query and each chunk together, catching semantic mismatches that vector distance misses.

```
User question
     │
     ▼
[Embed query]  →  ChromaDB cosine search  →  Top-20 candidates
                                                    │
                                         [Cohere cross-encoder]
                                                    │
                                              Top-5 re-ranked
                                                    │
                                    LLM + {history} + {context}
                                                    │
                                               Answer
```

**Measured impact** (RAGAS eval suite, 25 real estate Q&A pairs):

| Metric | Cosine only | + Cohere rerank | Delta |
|---|---|---|---|
| Faithfulness | 0.76 | **0.85** | **+0.09** |
| Answer Relevancy | 0.81 | **0.83** | +0.02 |

The 9-point faithfulness gain comes from Cohere surfacing penalty and default clauses that cosine similarity was burying below more topically broad chunks.

### Provider-Agnostic LLM Routing

`LangChainConfig` uses LangChain4j's `OpenAiChatModel` with a configurable `baseUrl`, making any OpenAI-compatible endpoint a drop-in replacement. The app ships pre-configured for Groq (free, ~300 tok/s) and switches to OpenAI with a single env var change.

```
LLM_BASE_URL=https://api.groq.com/openai/v1   →  Groq llama-3.3-70b (free)
LLM_BASE_URL=(unset)                           →  OpenAI GPT-4o (paid)
```

Same pattern applies to embeddings: Nomic Atlas (free) ↔ OpenAI text-embedding-3-small.

### Deal Analysis Intelligence

The deal analysis prompt instructs the LLM to:
1. **Infer the investment strategy** from documents (fix-and-flip vs. rental vs. wholesale)
2. **Apply the appropriate framework** automatically:
   - Fix & Flip → 70% Rule: `MAO = (0.70 × ARV) − Repairs`
   - Rental → Cap Rate: `NOI / Purchase Price` + Cash-on-Cash
   - Wholesale → Assignment spread and exit potential
3. **Extract all financials** from uploaded documents; estimate conservatively where unstated
4. **Flag contract terms** that directly affect deal economics (not just compliance)

### Conversation Memory

`MessageWindowChatMemory` maintains the last 10 turns per session in-process. The history string is injected into every Q&A prompt via `{history}` placeholder, enabling coherent multi-turn conversations without re-stating context.

### Observability

Every node calls `LangSmithService.startRun()` / `endRun()` asynchronously (fire-and-forget via WebFlux `subscribe()`). LangSmith captures: node latency, cosine scores, re-rank scores, verdict, token cost per chain run. Fails silently if the key is not set.

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| API | Spring Boot 3, Java 21 | Production-grade, virtual threads, type-safe records |
| AI orchestration | LangChain4j 0.35.0 | Java-native, no Python dependency |
| LLM | Groq (llama-3.3-70b) / OpenAI GPT-4o | Provider-agnostic via OpenAI-compatible API |
| Embeddings | Nomic nomic-embed-text-v1.5 / OpenAI text-embedding-3-small | Same provider-agnostic pattern |
| Vector store | ChromaDB 0.5.23 | Simple HTTP API, Docker-native, per-session collections |
| Re-ranking | Cohere rerank-english-v3.0 | Cross-encoder; proven +9pt faithfulness improvement |
| Web search | Tavily | Real-time market data for deal analysis |
| Observability | LangSmith | Per-node traces, latency, scores |
| Frontend | Next.js 14, TypeScript, Tailwind CSS | App Router, standalone Docker output |
| File parsing | pdfjs-dist, mammoth | Client-side PDF + DOCX text extraction |

---

## Quick Start

### Prerequisites
- Docker Desktop (all services run in containers)
- API keys (see [Environment Variables](#environment-variables))

### 1. Clone and configure

```bash
git clone <repo-url>
cd "AI Compliance agent"
cp .env.example .env
# Fill in your API keys in .env
```

### 2. Run

```bash
docker compose up --build
```

Docker Compose starts three services with health-gated startup ordering:
1. **ChromaDB** — waits for `/api/v1/heartbeat`
2. **Backend** — waits for ChromaDB healthy, then Spring Boot `/actuator/health`
3. **Frontend** — waits for backend healthy

Open **http://localhost:3000**

### Rebuild after code changes

```bash
docker compose up --build
```

---

## Local Development (without Docker)

### Backend

```bash
# Requires Java 21 + Maven
cd backend
mvn spring-boot:run
# API at http://localhost:8080
```

Set these in your shell (or a local `.env`):
```bash
export LLM_API_KEY=your_groq_key
export LLM_BASE_URL=https://api.groq.com/openai/v1
export LLM_MODEL=llama-3.3-70b-versatile
export EMBEDDING_API_KEY=your_nomic_key
export EMBEDDING_BASE_URL=https://api-atlas.nomic.ai/v1
export EMBEDDING_MODEL=nomic-embed-text-v1.5
export COHERE_API_KEY=your_cohere_key
```

Also start ChromaDB separately:
```bash
docker run -p 8000:8000 chromadb/chroma:0.5.23
```

### Frontend

```bash
cd frontend
npm install
npm run dev
# UI at http://localhost:3000
```

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `LLM_API_KEY` | Yes* | — | API key for the LLM provider |
| `LLM_BASE_URL` | No | (OpenAI) | Base URL for OpenAI-compatible LLM endpoint |
| `LLM_MODEL` | No | `gpt-4o` | Model name (e.g. `llama-3.3-70b-versatile`) |
| `OPENAI_API_KEY` | Yes* | — | OpenAI key — fallback if `LLM_API_KEY` not set |
| `EMBEDDING_API_KEY` | Yes* | — | API key for embedding provider |
| `EMBEDDING_BASE_URL` | No | (OpenAI) | Base URL for embedding endpoint |
| `EMBEDDING_MODEL` | No | `text-embedding-3-small` | Embedding model name |
| `COHERE_API_KEY` | Recommended | — | Cohere key for re-ranking. Without it, retrieval falls back to cosine-only (faithfulness 0.76 vs 0.85) |
| `LANGSMITH_API_KEY` | No | — | LangSmith tracing key. All tracing silently disabled if unset |
| `TAVILY_API_KEY` | No | — | Tavily key for live market search in Deal Analyzer. Without it, analysis uses uploaded documents only |
| `CHROMA_BASE_URL` | No | `http://localhost:8000` | ChromaDB URL. Automatically set to `http://chromadb:8000` in Docker |
| `RERANK_ENABLED` | No | `true` | Set `false` to disable Cohere re-ranking (useful for eval comparison) |

\* At least one of `LLM_API_KEY` or `OPENAI_API_KEY` must be set.

---

## API Reference

### Compliance Analyzer

#### `POST /api/compliance/analyze`

Ingests a document, runs compliance analysis, and opens a Q&A session.

**Request**
```json
{
  "documentText": "WHOLESALE PURCHASE AND SALE AGREEMENT...",
  "documentType": "wholesale_purchase_agreement"
}
```

**Headers:** `X-Tenant-ID: demo` (optional)

**Response**
```json
{
  "sessionId": "3f8a2b1c-...",
  "riskLevel": "HIGH",
  "summary": "This agreement carries elevated risk due to...",
  "findings": [
    {
      "clause": "DEFAULT — Section 7",
      "risk": "HIGH",
      "explanation": "Buyer's sole remedy is retention of earnest money...",
      "confidence": "HIGH"
    }
  ],
  "agentTrace": [
    { "node": "guardrail", "status": "VALID", "latencyMs": 412, "detail": "VALID" },
    { "node": "ingest",    "status": "OK",    "latencyMs": 891, "detail": "14 chunks ingested" },
    { "node": "analyze",   "status": "OK",    "latencyMs": 2341, "detail": "Risk: HIGH, Findings: 4" }
  ]
}
```

#### `POST /api/compliance/ask`

Ask a follow-up question. Conversation history is maintained automatically per session.

**Request**
```json
{
  "sessionId": "3f8a2b1c-...",
  "question": "What happens if the buyer defaults?"
}
```

**Response**
```json
{
  "answer": "Under Section 7, if the buyer fails to close...",
  "confidence": "HIGH",
  "sources": [
    { "text": "If Buyer fails to close...", "cosineScore": 0.87, "rerankScore": 0.94 }
  ],
  "rerankScores": [...],
  "agentTrace": [...]
}
```

#### `DELETE /api/compliance/session/{sessionId}`

Clears conversation memory and ChromaDB collection for a session.

---

### Deal Analyzer

#### `POST /api/deal/analyze`

Analyzes a multi-document deal package and returns a scored investment verdict.

**Request**
```json
{
  "documents": [
    {
      "name": "purchase_agreement.pdf",
      "text": "WHOLESALE PURCHASE AND SALE AGREEMENT...",
      "type": "Purchase Agreement"
    },
    {
      "name": "inspection_report.pdf",
      "text": "PROPERTY INSPECTION REPORT...",
      "type": "Inspection Report"
    }
  ],
  "address": "123 Oak St, Dallas, TX 75201",
  "askingPrice": 185000,
  "estimatedRepairs": 35000,
  "notes": "Seller is motivated, comparable sold at $280k last month"
}
```

**Response**
```json
{
  "sessionId": "7c4d1e9a-...",
  "verdict": "BUY",
  "score": 78,
  "strategy": "FIX_AND_FLIP",
  "framework": "70_PERCENT_RULE",
  "financials": {
    "askingPrice": 185000,
    "estimatedRepairs": 35000,
    "estimatedARV": 275000,
    "maxAllowableOffer": 157500,
    "projectedProfit": 55000,
    "roi": "29.7%",
    "projectedMonthlyRent": null,
    "capRate": null,
    "cashOnCash": null
  },
  "marketNotes": "Dallas TX market shows strong appreciation...",
  "riskFactors": ["Short inspection period (10 days)", "As-is clause limits recourse"],
  "complianceFlags": ["Assignment clause allows buyer to wholesale without seller consent"],
  "summary": "This fix-and-flip deal offers solid upside at a 29.7% ROI...",
  "recommendation": "Negotiate asking price to $157,500 to hit the 70% rule MAO...",
  "agentTrace": [...]
}
```

**Verdict scale:** `STRONG_BUY` (90-100) · `BUY` (70-89) · `MARGINAL` (50-69) · `PASS` (0-49)

---

## Supported Document Types

### Compliance Analyzer

| Value | Prompt | Focus Areas |
|---|---|---|
| `wholesale_purchase_agreement` | Real Estate | Earnest money, assignment rights, as-is, closing |
| `loan_agreement` | Real Estate | Interest rate, default, collateral, prepayment |
| `letter_of_intent` | Real Estate | Non-binding language, exclusivity, contingencies |
| `commercial_sales_agreement` | Real Estate | Price, representations, warranties, title |
| `residential_lease` | Real Estate | Rent, term, maintenance, termination |
| `executive_developer_program` | Real Estate | Development terms, milestones, financing |
| `design_construction_agreement` | General | Payment schedule, change orders, warranties |
| `vendor_contract` | General | SLA, termination, liability cap, IP |
| `nda` | General | Scope, exclusions, term, remedies |

### Deal Analyzer

Accepts any document type. Label each file during upload so the LLM knows what it's reading: `Purchase Agreement`, `Inspection Report`, `Comparable Sales (Comps)`, `Market Report`, `Title Report`, `Loan Documents`, `Property Tax Records`, `Other`.

---

## Running RAGAS Evaluations

```bash
cd evals
pip install -r requirements.txt

# Baseline: cosine retrieval only (set RERANK_ENABLED=false in .env first)
python eval_pipeline.py --mode baseline

# Re-ranked: with Cohere cross-encoder
python eval_pipeline.py --mode reranked

# Compare both runs side by side
python eval_pipeline.py --mode compare
```

Results are written to `baseline_results.json` and `reranked_results.json`.

---

---

## Project Structure

```
AI Compliance agent/
├── backend/                          Spring Boot 3 API
│   └── src/main/java/com/compliance/agent/
│       ├── agent/
│       │   ├── AgentState.java                 Immutable state record flowing through nodes
│       │   ├── ComplianceAgentOrchestrator.java 4-node compliance pipeline
│       │   └── DealAnalyzerOrchestrator.java    3-node deal analysis pipeline
│       ├── config/
│       │   ├── CorsConfig.java                 CORS for frontend origin
│       │   ├── LangChainConfig.java            OpenAI-compatible LLM + embedding beans
│       │   └── WebClientConfig.java            Shared reactive HTTP client
│       ├── controller/
│       │   ├── ComplianceController.java       POST /api/compliance/*
│       │   └── DealController.java             POST /api/deal/analyze
│       ├── model/
│       │   ├── Models.java                     Compliance request/response records
│       │   └── DealModels.java                 Deal request/response records
│       ├── prompt/
│       │   ├── PromptTemplates.java            Guardrail, compliance, Q&A prompts
│       │   └── DealPromptTemplates.java        Deal analysis prompt with framework guidance
│       └── service/
│           ├── RagService.java                 Embed → ChromaDB (single + multi-doc)
│           ├── RerankService.java              Cohere cross-encoder re-ranking
│           ├── ConversationMemoryService.java  Per-session MessageWindowChatMemory
│           ├── WebSearchService.java           Tavily market data search
│           └── LangSmithService.java           Async observability traces
│
├── frontend/                         Next.js 14 TypeScript
│   └── app/
│       ├── page.tsx                  Tab routing (Compliance / Deal Analyzer)
│       └── components/
│           ├── DealAnalyzer.tsx      Multi-doc upload, context form, results display
│           ├── FileUpload.tsx        Client-side PDF/DOCX/TXT parser (pdfjs + mammoth)
│           ├── QAPanel.tsx           Multi-turn Q&A with source citations
│           ├── ClauseList.tsx        Risk-colored findings list
│           ├── RiskBadge.tsx         HIGH/MEDIUM/LOW badge
│           ├── AgentTrace.tsx        Node execution timeline
│           └── SourceChunk.tsx       Retrieved chunk with cosine + rerank scores
│
├── evals/                            RAGAS evaluation pipeline
│   ├── eval_pipeline.py              Baseline vs re-ranked comparison
│   ├── test_cases.json               25 real estate Q&A pairs
│   ├── baseline_results.json         Cosine-only results
│   └── reranked_results.json         Cohere re-ranked results
│
├── docker-compose.yml                Health-gated 3-service stack
├── .env.example                      Full variable reference with free-tier setup
└── CLAUDE.md                         Codebase guide for AI assistants
```

---

## Resume Bullets

- Designed and built a two-stage RAG pipeline (ChromaDB cosine retrieval → Cohere cross-encoder re-ranking) — quantified a 9-point improvement in RAGAS faithfulness (0.76 → 0.85) on a 25-pair eval suite of real estate contract Q&A
- Built a LangGraph-style 4-node agent state machine in Java (guardrail → ingest → analyze → Q&A) with full LangSmith observability — traces every node's latency, cosine scores, re-rank scores, and token cost asynchronously
- Implemented provider-agnostic LLM routing via OpenAI-compatible API abstraction — same codebase runs on Groq llama-3.3-70b (free) and OpenAI GPT-4o (paid) with two env var changes
- Built a multi-document deal analyzer that automatically infers investment strategy (fix-and-flip / rental / wholesale) and applies the appropriate financial framework (70% Rule, Cap Rate, Cash-on-Cash) — extends the RAG pipeline with optional Tavily live market search
- Instrumented end-to-end with LangSmith: all observability calls are fire-and-forget via WebFlux reactive subscriptions — zero latency impact on the critical path
- Shipped as a fully containerized 3-service Docker stack (ChromaDB + Spring Boot + Next.js) with health-gated startup ordering and a free-tier deployment path requiring no credit card

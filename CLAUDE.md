# AI Compliance Document Review Agent

Java 21 · Spring Boot 3 · LangChain4j · ChromaDB · Cohere · OpenAI · LangSmith · Next.js 14

## Project layout

```
backend/   Spring Boot 3 API — port 8080
frontend/  Next.js 14 TypeScript — port 3000
evals/     RAGAS evaluation pipeline (Python)
```

## Running locally

```bash
# 1. Start ChromaDB
docker compose up chromadb

# 2. Backend (requires OPENAI_API_KEY, COHERE_API_KEY, LANGSMITH_API_KEY)
cd backend && mvn spring-boot:run

# 3. Frontend
cd frontend && npm install && npm run dev

# Or: all services
docker compose up --build
```

## Key env vars

| Variable | Required | Description |
|----------|----------|-------------|
| OPENAI_API_KEY | Yes | GPT-4o + embeddings |
| COHERE_API_KEY | Yes | Rerank API |
| LANGSMITH_API_KEY | No | Observability traces |
| CHROMA_BASE_URL | No | Default http://localhost:8000 |
| RERANK_ENABLED | No | Default true |

## Backend package structure

`com.compliance.agent`
- `controller/` — REST endpoints
- `service/` — RagService, RerankService, ConversationMemoryService, LangSmithService
- `agent/` — ComplianceAgentOrchestrator, AgentState
- `prompt/` — PromptTemplates
- `model/` — request/response records
- `config/` — Spring beans (LangChain, CORS, WebClient)

## Testing the API

```bash
# Analyze a document
curl -X POST http://localhost:8080/api/compliance/analyze \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: demo" \
  -d '{"documentText":"...","documentType":"wholesale_purchase_agreement"}'

# Follow-up Q&A
curl -X POST http://localhost:8080/api/compliance/ask \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<id>","question":"What are the payment terms?"}'
```

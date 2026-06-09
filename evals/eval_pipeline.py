"""
RAGAS evaluation pipeline for the Compliance Agent.

Calls the live backend API to collect RAG data, then scores it with RAGAS.
Requires the backend to be running (docker compose up or mvn spring-boot:run).

Usage:
  python eval_pipeline.py --mode baseline   # rerank disabled (RERANK_ENABLED=false)
  python eval_pipeline.py --mode reranked   # rerank enabled  (RERANK_ENABLED=true)
  python eval_pipeline.py --mode compare    # print both result files side-by-side

LLM provider: Groq (free) via LLM_API_KEY / LLM_BASE_URL in .env
Embeddings:   Nomic Atlas (free) via EMBEDDING_API_KEY / EMBEDDING_BASE_URL in .env
No OpenAI key required.
"""

# rag_eval_utils must be imported first — it applies the RAGAS compatibility patch
# before any ragas module is loaded.
import rag_eval_utils  # noqa: F401  (side-effect import)

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env")

API_BASE       = os.getenv("API_BASE_URL",      "http://localhost:8080")
LLM_API_KEY    = os.getenv("LLM_API_KEY",       os.getenv("OPENAI_API_KEY", ""))
LLM_BASE_URL   = os.getenv("LLM_BASE_URL",      "")
LLM_MODEL      = os.getenv("LLM_MODEL",         "gpt-4o-mini")
EMBED_API_KEY  = os.getenv("EMBEDDING_API_KEY", os.getenv("OPENAI_API_KEY", ""))
EMBED_BASE_URL = os.getenv("EMBEDDING_BASE_URL","")
EMBED_MODEL    = os.getenv("EMBEDDING_MODEL",   "text-embedding-3-small")

TEST_CASES_PATH = Path(__file__).parent / "test_cases.json"
RESULTS_DIR     = Path(__file__).parent
RAGAS_METRICS   = ("faithfulness", "answer_relevancy", "context_precision")


# ── backend calls ─────────────────────────────────────────────────────────────

def analyze_document(document_text: str, document_type: str) -> dict:
    resp = requests.post(
        f"{API_BASE}/api/compliance/analyze",
        json={"documentText": document_text, "documentType": document_type},
        headers={"X-Tenant-ID": "eval"},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()


def ask_question(session_id: str, question: str) -> dict:
    resp = requests.post(
        f"{API_BASE}/api/compliance/ask",
        json={"sessionId": session_id, "question": question},
        headers={"X-Tenant-ID": "eval"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()


def clear_session(session_id: str) -> None:
    try:
        requests.delete(f"{API_BASE}/api/compliance/session/{session_id}", timeout=10)
    except Exception:
        pass


# ── data collection ───────────────────────────────────────────────────────────

def collect_rag_data(test_cases: list[dict]) -> list[dict]:
    results = []
    for i, tc in enumerate(test_cases):
        print(f"  [{i + 1}/{len(test_cases)}] {tc['id']}: {tc['question'][:60]}...")
        try:
            analyze_resp = analyze_document(tc["document_excerpt"], tc["document_type"])
            session_id   = analyze_resp["sessionId"]
            ask_resp     = ask_question(session_id, tc["question"])

            results.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       ask_resp.get("answer", ""),
                "contexts":     [s["text"] for s in ask_resp.get("sources", [])],
                "ground_truth": tc["ground_truth"],
                "rerank_scores": ask_resp.get("rerankScores", []),
            })
            clear_session(session_id)
            time.sleep(0.5)
        except Exception as e:
            print(f"    ERROR on {tc['id']}: {e}")
            results.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       "",
                "contexts":     [],
                "ground_truth": tc["ground_truth"],
                "error":        str(e),
            })
    return results


# ── RAGAS evaluation ──────────────────────────────────────────────────────────

def run_ragas(rag_data: list[dict]) -> dict:
    from datasets import Dataset
    from ragas import evaluate, RunConfig
    from ragas.metrics import faithfulness, answer_relevancy, context_precision
    from rag_eval_utils import build_ragas_llm_and_embeddings

    valid = [d for d in rag_data if d.get("contexts") and d.get("answer")]
    if not valid:
        print("  No valid rows for RAGAS (all test cases returned errors).")
        return {m: 0.0 for m in RAGAS_METRICS} | {"evaluated_count": 0}

    dataset = Dataset.from_list([
        {
            "question":     d["question"],
            "answer":       d["answer"],
            "contexts":     d["contexts"],
            "ground_truth": d["ground_truth"],
        }
        for d in valid
    ])

    ragas_llm, ragas_emb = build_ragas_llm_and_embeddings(
        LLM_API_KEY, LLM_BASE_URL, LLM_MODEL,
        EMBED_API_KEY, EMBED_BASE_URL, EMBED_MODEL,
    )

    result = evaluate(
        dataset=dataset,
        metrics=[faithfulness, answer_relevancy, context_precision],
        llm=ragas_llm,
        embeddings=ragas_emb,
        run_config=RunConfig(max_workers=1, max_retries=5, max_wait=90),
    )

    from rag_eval_utils import safe_ragas_score
    return {
        "faithfulness":      safe_ragas_score(result["faithfulness"]),
        "answer_relevancy":  safe_ragas_score(result["answer_relevancy"]),
        "context_precision": safe_ragas_score(result["context_precision"]),
        "evaluated_count":   len(valid),
    }


# ── result persistence ────────────────────────────────────────────────────────

def save_results(mode: str, rag_data: list[dict], scores: dict) -> dict:
    output = {
        "mode":       mode,
        "timestamp":  datetime.now(timezone.utc).isoformat(),
        "scores":     scores,
        "test_cases": rag_data,
    }
    path = RESULTS_DIR / f"{mode}_results.json"
    with open(path, "w") as f:
        json.dump(output, f, indent=2)
    print(f"\n  Results saved → {path}")
    return output


def compare_results() -> None:
    baseline_path = RESULTS_DIR / "baseline_results.json"
    reranked_path = RESULTS_DIR / "reranked_results.json"

    missing = [p for p in (baseline_path, reranked_path) if not p.exists()]
    if missing:
        print(f"Missing result files: {[p.name for p in missing]}")
        print("Run --mode baseline and --mode reranked first.")
        return

    with open(baseline_path)  as f: baseline  = json.load(f)
    with open(reranked_path)  as f: reranked  = json.load(f)

    b, r = baseline["scores"], reranked["scores"]
    print("\n" + "=" * 62)
    print("RAGAS Evaluation Comparison")
    print("=" * 62)
    print(f"  {'Metric':<25} {'Baseline':>12} {'Re-ranked':>12} {'Delta':>10}")
    print("-" * 62)
    for m in RAGAS_METRICS:
        bv, rv = b.get(m, 0.0), r.get(m, 0.0)
        delta  = rv - bv
        sign   = "+" if delta >= 0 else ""
        label  = m.replace("_", " ").title()
        print(f"  {label:<25} {bv:>12.4f} {rv:>12.4f} {sign}{delta:>9.4f}")
    print("=" * 62)
    print(f"  Baseline  timestamp : {baseline['timestamp']}")
    print(f"  Re-ranked timestamp : {reranked['timestamp']}")
    print()


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="RAGAS eval pipeline for the Compliance Agent")
    parser.add_argument(
        "--mode",
        choices=["baseline", "reranked", "compare"],
        required=True,
        help="baseline = rerank disabled; reranked = rerank enabled; compare = diff",
    )
    args = parser.parse_args()

    if args.mode == "compare":
        compare_results()
        return

    if not LLM_API_KEY:
        print("ERROR: LLM_API_KEY (or OPENAI_API_KEY) is not set in .env")
        sys.exit(1)

    print(f"\nRunning RAGAS eval  mode={args.mode}")
    print(f"Backend : {API_BASE}")
    print(f"LLM     : {LLM_MODEL}  ({'custom endpoint' if LLM_BASE_URL else 'OpenAI'})")
    print(f"Embeds  : {EMBED_MODEL}  ({'custom endpoint' if EMBED_BASE_URL else 'OpenAI'})")
    print()

    with open(TEST_CASES_PATH) as f:
        test_cases = json.load(f)

    print(f"Step 1/2 — collecting RAG data from backend ({len(test_cases)} cases)...")
    rag_data = collect_rag_data(test_cases)

    errors = sum(1 for d in rag_data if "error" in d)
    if errors:
        print(f"  Warning: {errors}/{len(test_cases)} cases had errors.")

    print("\nStep 2/2 — running RAGAS evaluation...")
    scores = run_ragas(rag_data)

    print()
    for m in RAGAS_METRICS:
        print(f"  {m.replace('_', ' ').title():<22}: {scores[m]:.4f}")
    print(f"  Evaluated cases      : {scores['evaluated_count']}")

    save_results(args.mode, rag_data, scores)


if __name__ == "__main__":
    main()

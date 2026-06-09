"""
RAGAS evaluation pipeline for the Compliance Agent.

Usage:
  python eval_pipeline.py --mode baseline    # with rerank disabled
  python eval_pipeline.py --mode reranked    # with rerank enabled
  python eval_pipeline.py --mode compare     # print both result files side-by-side

Prerequisites:
  pip install -r requirements.txt
  Set OPENAI_API_KEY and ensure the backend is running on http://localhost:8080
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env")

API_BASE = os.getenv("API_BASE_URL", "http://localhost:8080")
OPENAI_API_KEY   = os.getenv("OPENAI_API_KEY", "")
LLM_API_KEY      = os.getenv("LLM_API_KEY", OPENAI_API_KEY)
LLM_BASE_URL     = os.getenv("LLM_BASE_URL", "")          # empty = OpenAI default
LLM_MODEL        = os.getenv("LLM_MODEL", "gpt-4o-mini")
EMBED_API_KEY    = os.getenv("EMBEDDING_API_KEY", OPENAI_API_KEY)
EMBED_BASE_URL   = os.getenv("EMBEDDING_BASE_URL", "")     # empty = OpenAI default
EMBED_MODEL      = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")
TEST_CASES_PATH = Path(__file__).parent / "test_cases.json"
RESULTS_DIR = Path(__file__).parent
RAGAS_METRICS = ("faithfulness", "answer_relevancy", "context_precision")


def format_metric_name(metric: str) -> str:
    return metric.replace("_", " ").title()


def load_test_cases() -> list[dict]:
    with open(TEST_CASES_PATH) as f:
        return json.load(f)


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


def clear_session(session_id: str):
    try:
        requests.delete(f"{API_BASE}/api/compliance/session/{session_id}", timeout=10)
    except Exception:
        pass


def collect_rag_data(test_cases: list[dict]) -> list[dict]:
    """Call the backend for each test case and collect question, answer, contexts."""
    results = []
    for i, tc in enumerate(test_cases):
        print(f"  [{i+1}/{len(test_cases)}] {tc['id']}: {tc['question'][:60]}...")
        try:
            analyze_resp = analyze_document(tc["document_excerpt"], tc["document_type"])
            session_id = analyze_resp["sessionId"]

            ask_resp = ask_question(session_id, tc["question"])

            contexts = [s["text"] for s in ask_resp.get("sources", [])]
            answer = ask_resp.get("answer", "")
            rerank_scores = ask_resp.get("rerankScores", [])

            results.append({
                "id": tc["id"],
                "question": tc["question"],
                "answer": answer,
                "contexts": contexts,
                "ground_truth": tc["ground_truth"],
                "rerank_scores": rerank_scores,
            })

            clear_session(session_id)
            time.sleep(0.5)  # avoid rate-limiting on free Cohere tier

        except Exception as e:
            print(f"    ERROR on {tc['id']}: {e}")
            results.append({
                "id": tc["id"],
                "question": tc["question"],
                "answer": "",
                "contexts": [],
                "ground_truth": tc["ground_truth"],
                "error": str(e),
            })

    return results


def run_ragas(rag_data: list[dict]) -> dict:
    """Run RAGAS faithfulness, answer_relevancy, and context_precision."""
    try:
        from datasets import Dataset
        from ragas import evaluate
        from ragas.metrics import faithfulness, answer_relevancy, context_precision
        from langchain_openai import ChatOpenAI, OpenAIEmbeddings

        valid = [d for d in rag_data if d.get("contexts") and d.get("answer")]
        if not valid:
            print("  No valid data rows for RAGAS evaluation.")
            return {
                "faithfulness": 0.0,
                "answer_relevancy": 0.0,
                "context_precision": 0.0,
                "evaluated_count": 0,
            }

        dataset = Dataset.from_list([
            {
                "question": d["question"],
                "answer": d["answer"],
                "contexts": d["contexts"],
                "ground_truth": d["ground_truth"],
            }
            for d in valid
        ])

        llm_kwargs = {"api_key": LLM_API_KEY, "model": LLM_MODEL}
        if LLM_BASE_URL:
            llm_kwargs["base_url"] = LLM_BASE_URL
        llm = ChatOpenAI(**llm_kwargs)

        emb_kwargs = {"api_key": EMBED_API_KEY, "model": EMBED_MODEL}
        if EMBED_BASE_URL:
            emb_kwargs["base_url"] = EMBED_BASE_URL
        embeddings = OpenAIEmbeddings(**emb_kwargs)

        result = evaluate(
            dataset=dataset,
            metrics=[faithfulness, answer_relevancy, context_precision],
            llm=llm,
            embeddings=embeddings,
        )

        return {
            "faithfulness": float(result["faithfulness"]),
            "answer_relevancy": float(result["answer_relevancy"]),
            "context_precision": float(result["context_precision"]),
            "evaluated_count": len(valid),
        }

    except ImportError as e:
        print(f"  RAGAS import failed: {e}. Install requirements.txt first.")
        return {
            "faithfulness": 0.0,
            "answer_relevancy": 0.0,
            "context_precision": 0.0,
            "evaluated_count": 0,
        }


def save_results(mode: str, rag_data: list[dict], scores: dict):
    output = {
        "mode": mode,
        "timestamp": datetime.utcnow().isoformat() + "Z",
        "scores": scores,
        "test_cases": rag_data,
    }
    path = RESULTS_DIR / f"{mode}_results.json"
    with open(path, "w") as f:
        json.dump(output, f, indent=2)
    print(f"\n  Results saved to {path}")
    return output


def compare_results():
    baseline_path = RESULTS_DIR / "baseline_results.json"
    reranked_path = RESULTS_DIR / "reranked_results.json"

    if not baseline_path.exists() or not reranked_path.exists():
        print("Run both --mode baseline and --mode reranked first.")
        return

    with open(baseline_path) as f:
        baseline = json.load(f)
    with open(reranked_path) as f:
        reranked = json.load(f)

    b_scores = baseline["scores"]
    r_scores = reranked["scores"]

    print("\n" + "=" * 60)
    print("RAGAS Evaluation Comparison")
    print("=" * 60)
    print(f"{'Metric':<25} {'Baseline':>12} {'Re-ranked':>12} {'Delta':>10}")
    print("-" * 60)
    for metric in RAGAS_METRICS:
        bv = b_scores.get(metric, 0)
        rv = r_scores.get(metric, 0)
        delta = rv - bv
        sign = "+" if delta >= 0 else ""
        print(f"  {format_metric_name(metric):<23} {bv:>12.4f} {rv:>12.4f} {sign}{delta:>9.4f}")
    print("=" * 60)
    print(f"  Baseline timestamp:  {baseline['timestamp']}")
    print(f"  Re-ranked timestamp: {reranked['timestamp']}")
    print()


def main():
    parser = argparse.ArgumentParser(description="RAGAS eval pipeline for Compliance Agent")
    parser.add_argument(
        "--mode",
        choices=["baseline", "reranked", "compare"],
        required=True,
        help="baseline = rerank disabled; reranked = rerank enabled; compare = show diff",
    )
    args = parser.parse_args()

    if args.mode == "compare":
        compare_results()
        return

    if not LLM_API_KEY:
        print("ERROR: Neither LLM_API_KEY nor OPENAI_API_KEY is set.")
        sys.exit(1)

    print(f"\nRunning RAGAS eval in mode: {args.mode}")
    print(f"API: {API_BASE}")
    print(f"Test cases: {TEST_CASES_PATH}")
    print()

    print("Step 1: Collecting RAG data from backend...")
    test_cases = load_test_cases()
    rag_data = collect_rag_data(test_cases)

    print("\nStep 2: Running RAGAS evaluation...")
    scores = run_ragas(rag_data)

    for metric in RAGAS_METRICS:
        print(f"  {format_metric_name(metric):<18}: {scores[metric]:.4f}")
    print(f"  Evaluated cases:   {scores['evaluated_count']}")

    save_results(args.mode, rag_data, scores)


if __name__ == "__main__":
    main()

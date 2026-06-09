"""
Chunk strategy sweep — RAGAS evaluation across multiple chunking configurations.

Tests flat chunking at sizes 200 / 500 / 750 / 1000 chars and a parent-child
strategy (child=150 indexed, parent=600 returned to LLM).  Each document type's
excerpts are merged into one composite document so that chunk boundaries have a
real effect on retrieval quality.

Runs entirely standalone — no backend required.
LLM judge  : Groq llama-3.3-70b-versatile (free tier)
Embeddings : Nomic Atlas nomic-embed-text-v1.5 (free tier)

Usage:
  python chunk_sweep.py               # full sweep, all 20 test cases
  python chunk_sweep.py --quick       # first 8 test cases (~10 min)
  python chunk_sweep.py --parent-child-only  # skip flat sweep, run PC only

Output: chunk_sweep_results.json + printed comparison table
"""

# rag_eval_utils must be imported first — applies the RAGAS compatibility patch.
import rag_eval_utils  # noqa: F401

import argparse
import json
import os
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env")

LLM_API_KEY    = os.getenv("LLM_API_KEY",        "")
LLM_BASE_URL   = os.getenv("LLM_BASE_URL",       "https://api.groq.com/openai/v1")
LLM_MODEL      = os.getenv("LLM_MODEL",          "llama-3.3-70b-versatile")
EMBED_API_KEY  = os.getenv("EMBEDDING_API_KEY",  "")
EMBED_BASE_URL = os.getenv("EMBEDDING_BASE_URL", "https://api-atlas.nomic.ai/v1")
EMBED_MODEL    = os.getenv("EMBEDDING_MODEL",    "nomic-embed-text-v1.5")
# RAGAS makes ~3 LLM calls per sample. Use a lighter model with a separate
# daily quota (500k tokens/day on Groq free tier) so the judge does not
# exhaust the same budget as the answer-generation model.
RAGAS_LLM_MODEL = os.getenv("RAGAS_LLM_MODEL",  "llama-3.1-8b-instant")

FLAT_CHUNK_SIZES  = [200, 500, 750, 1000]
PARENT_CHUNK_SIZE = 600
CHILD_CHUNK_SIZE  = 150
TOP_K             = 3
RAGAS_METRICS     = ("faithfulness", "answer_relevancy", "context_precision")

TEST_CASES_PATH = Path(__file__).parent / "test_cases.json"
RESULTS_PATH    = Path(__file__).parent / "chunk_sweep_results.json"


# ── embedding helpers ─────────────────────────────────────────────────────────

def embed_batch(texts: list[str], task_type: str = "search_document") -> list[list[float]]:
    resp = requests.post(
        f"{EMBED_BASE_URL}/embedding/text",
        headers={"Authorization": f"Bearer {EMBED_API_KEY}", "Content-Type": "application/json"},
        json={"model": EMBED_MODEL, "texts": texts, "task_type": task_type},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["embeddings"]


# ── LLM helper ────────────────────────────────────────────────────────────────

def generate_answer(question: str, context: str, retries: int = 4) -> str:
    messages = [
        {
            "role": "system",
            "content": (
                "You are a legal compliance expert. "
                "Answer questions using only the provided context. Be concise and accurate."
            ),
        },
        {"role": "user", "content": f"Context:\n{context}\n\nQuestion: {question}"},
    ]
    for attempt in range(retries):
        try:
            resp = requests.post(
                f"{LLM_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {LLM_API_KEY}", "Content-Type": "application/json"},
                json={"model": LLM_MODEL, "messages": messages,
                      "temperature": 0.1, "max_tokens": 300},
                timeout=45,
            )
            resp.raise_for_status()
            return resp.json()["choices"][0]["message"]["content"].strip()
        except requests.exceptions.HTTPError as e:
            if e.response is not None and e.response.status_code == 429 and attempt < retries - 1:
                wait = 4.0 * (2 ** attempt)
                print(f"    rate-limited, retrying in {wait:.0f}s...")
                time.sleep(wait)
            else:
                raise


# ── chunking ──────────────────────────────────────────────────────────────────

def chunk_text(text: str, chunk_size: int, overlap: int) -> list[str]:
    """Sliding-window character chunker with overlap."""
    chunks, start = [], 0
    while start < len(text):
        end   = min(start + chunk_size, len(text))
        chunk = text[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end == len(text):
            break
        start += chunk_size - overlap
    return chunks


# ── retrieval ─────────────────────────────────────────────────────────────────

def cosine_sim(a: list[float], b: list[float]) -> float:
    va, vb = np.array(a), np.array(b)
    return float(np.dot(va, vb) / (np.linalg.norm(va) * np.linalg.norm(vb) + 1e-9))


def retrieve_top_k(
    query_emb: list[float],
    chunk_embs: list[list[float]],
    chunks: list[str],
    k: int,
) -> list[str]:
    ranked = sorted(range(len(chunk_embs)),
                    key=lambda i: cosine_sim(query_emb, chunk_embs[i]),
                    reverse=True)
    return [chunks[i] for i in ranked[:k]]


# ── parent-child index ────────────────────────────────────────────────────────

def build_parent_child_index(
    doc: str,
) -> tuple[list[str], list[list[float]], list[int]]:
    """
    Returns (parent_chunks, child_embeddings, child_to_parent_index).
    Children are embedded; at query time we retrieve children then return parents.
    """
    parents  = chunk_text(doc, PARENT_CHUNK_SIZE, max(1, PARENT_CHUNK_SIZE // 10))
    children, child_to_parent = [], []
    for pid, parent in enumerate(parents):
        for child in chunk_text(parent, CHILD_CHUNK_SIZE, max(1, CHILD_CHUNK_SIZE // 5)):
            children.append(child)
            child_to_parent.append(pid)

    child_embs = embed_batch(children)
    return parents, child_embs, child_to_parent


def retrieve_parent_child(
    query_emb: list[float],
    parents: list[str],
    child_embs: list[list[float]],
    child_to_parent: list[int],
    k: int,
) -> list[str]:
    ranked = sorted(range(len(child_embs)),
                    key=lambda i: cosine_sim(query_emb, child_embs[i]),
                    reverse=True)
    seen, result = set(), []
    for idx in ranked:
        pid = child_to_parent[idx]
        if pid not in seen:
            seen.add(pid)
            result.append(parents[pid])
        if len(result) == k:
            break
    return result


# ── RAGAS evaluation ──────────────────────────────────────────────────────────

def run_ragas(rag_data: list[dict], label: str = "") -> dict:
    from datasets import Dataset
    from ragas import evaluate, RunConfig
    from ragas.metrics import faithfulness, answer_relevancy, context_precision
    from rag_eval_utils import build_ragas_llm_and_embeddings, safe_ragas_score

    valid = [d for d in rag_data if d.get("contexts") and d.get("answer")]
    if not valid:
        print("  No valid rows for RAGAS.")
        return {m: 0.0 for m in RAGAS_METRICS} | {"evaluated_count": 0}

    print(f"  Running RAGAS{' — ' + label if label else ''} ({len(valid)} valid rows, judge={RAGAS_LLM_MODEL})...")

    dataset = Dataset.from_list([
        {
            "question":     d["question"],
            "answer":       d["answer"],
            "contexts":     d["contexts"],
            "ground_truth": d["ground_truth"],
        }
        for d in valid
    ])

    # Use a lighter judge model (separate Groq daily quota) so RAGAS scoring
    # does not exhaust the same token budget as answer generation.
    ragas_llm, ragas_emb = build_ragas_llm_and_embeddings(
        LLM_API_KEY, LLM_BASE_URL, RAGAS_LLM_MODEL,
        EMBED_API_KEY, EMBED_BASE_URL, EMBED_MODEL,
    )

    result = evaluate(
        dataset=dataset,
        metrics=[faithfulness, answer_relevancy, context_precision],
        llm=ragas_llm,
        embeddings=ragas_emb,
        run_config=RunConfig(max_workers=1, max_retries=5, max_wait=90),
    )

    scores = {
        "faithfulness":      safe_ragas_score(result["faithfulness"]),
        "answer_relevancy":  safe_ragas_score(result["answer_relevancy"]),
        "context_precision": safe_ragas_score(result["context_precision"]),
        "evaluated_count":   len(valid),
    }
    for m in RAGAS_METRICS:
        print(f"    {m:<22}: {scores[m]:.4f}")
    return scores


# ── sweep runners ─────────────────────────────────────────────────────────────

def _collect_rag_data(
    test_cases: list[dict],
    doc_chunks: dict[str, list[str]],
    doc_embs: dict[str, list[list[float]]],
) -> list[dict]:
    rag_data: list[dict] = []
    for i, tc in enumerate(test_cases):
        print(f"  [{i + 1:02}/{len(test_cases)}] {tc['id']}: {tc['question'][:55]}...")
        dt = tc["document_type"]
        try:
            q_emb      = embed_batch([tc["question"]], task_type="search_query")[0]
            top_chunks = retrieve_top_k(q_emb, doc_embs[dt], doc_chunks[dt], TOP_K)
            answer     = generate_answer(tc["question"], "\n---\n".join(top_chunks))
            rag_data.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       answer,
                "contexts":     top_chunks,
                "ground_truth": tc["ground_truth"],
            })
        except Exception as e:
            print(f"    ERROR: {e}")
            rag_data.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       "",
                "contexts":     [],
                "ground_truth": tc["ground_truth"],
                "error":        str(e),
            })
        time.sleep(2.0)
    return rag_data


def run_flat_sweep(
    test_cases: list[dict],
    composite_docs: dict[str, str],
    results: dict,
) -> None:
    for chunk_size in FLAT_CHUNK_SIZES:
        overlap = max(1, chunk_size // 10)
        print(f"\n{'=' * 60}")
        print(f"Flat  chunk_size={chunk_size}  overlap={overlap}")
        print(f"{'=' * 60}")

        doc_chunks: dict[str, list[str]] = {}
        doc_embs:   dict[str, list[list[float]]] = {}
        for dt, doc in composite_docs.items():
            chunks = chunk_text(doc, chunk_size, overlap)
            doc_chunks[dt] = chunks
            doc_embs[dt]   = embed_batch(chunks)
            print(f"  {dt}: {len(chunks)} chunks")
            time.sleep(0.3)

        rag_data = _collect_rag_data(test_cases, doc_chunks, doc_embs)
        scores   = run_ragas(rag_data, label=f"flat chunk_size={chunk_size}")

        results[f"flat_{chunk_size}"] = {
            "strategy":   "flat",
            "chunk_size": chunk_size,
            "overlap":    overlap,
            "scores":     scores,
            "test_cases": rag_data,
            "timestamp":  datetime.now(timezone.utc).isoformat(),
        }


def run_parent_child_sweep(
    test_cases: list[dict],
    composite_docs: dict[str, str],
    results: dict,
) -> None:
    print(f"\n{'=' * 60}")
    print(f"Parent-child  parent={PARENT_CHUNK_SIZE}  child={CHILD_CHUNK_SIZE}")
    print(f"{'=' * 60}")

    pc_index: dict[str, tuple] = {}
    for dt, doc in composite_docs.items():
        parents, child_embs, c2p = build_parent_child_index(doc)
        pc_index[dt] = (parents, child_embs, c2p)
        print(f"  {dt}: {len(parents)} parents  child_size={CHILD_CHUNK_SIZE}")
        time.sleep(0.3)

    rag_data: list[dict] = []
    for i, tc in enumerate(test_cases):
        print(f"  [{i + 1:02}/{len(test_cases)}] {tc['id']}: {tc['question'][:55]}...")
        dt = tc["document_type"]
        parents, child_embs, c2p = pc_index[dt]
        try:
            q_emb      = embed_batch([tc["question"]], task_type="search_query")[0]
            top_chunks = retrieve_parent_child(q_emb, parents, child_embs, c2p, TOP_K)
            answer     = generate_answer(tc["question"], "\n---\n".join(top_chunks))
            rag_data.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       answer,
                "contexts":     top_chunks,
                "ground_truth": tc["ground_truth"],
            })
        except Exception as e:
            print(f"    ERROR: {e}")
            rag_data.append({
                "id":           tc["id"],
                "question":     tc["question"],
                "answer":       "",
                "contexts":     [],
                "ground_truth": tc["ground_truth"],
                "error":        str(e),
            })
        time.sleep(2.0)

    scores = run_ragas(rag_data, label="parent-child")

    results["parent_child"] = {
        "strategy":          "parent_child",
        "parent_chunk_size": PARENT_CHUNK_SIZE,
        "child_chunk_size":  CHILD_CHUNK_SIZE,
        "scores":            scores,
        "test_cases":        rag_data,
        "timestamp":         datetime.now(timezone.utc).isoformat(),
    }


# ── comparison table ──────────────────────────────────────────────────────────

def print_comparison_table(results: dict) -> None:
    keys   = [f"flat_{cs}" for cs in FLAT_CHUNK_SIZES] + ["parent_child"]
    labels = [str(cs) for cs in FLAT_CHUNK_SIZES] + [f"PC({CHILD_CHUNK_SIZE}→{PARENT_CHUNK_SIZE})"]
    present = [k for k in keys if k in results]
    col_w  = 14

    width = 27 + col_w * len(present)
    print("\n" + "=" * width)
    print("Chunking Strategy Comparison — RAGAS Scores")
    print("=" * width)

    present_labels = [labels[keys.index(k)] for k in present]
    print(f"  {'Metric':<23}" + "".join(f"{lbl:>{col_w}}" for lbl in present_labels))
    print("-" * width)

    for m in RAGAS_METRICS:
        vals = [results[k]["scores"].get(m, 0.0) for k in present]
        best = max(vals) if vals else 0.0
        row  = f"  {m.replace('_', ' ').title():<23}"
        for v in vals:
            marker = " *" if abs(v - best) < 1e-9 else "  "
            row   += f"{v:>{col_w - 2}.4f}{marker}"
        print(row)

    print("=" * width)
    print("  * = best score for that metric\n")

    avg_scores = {
        k: sum(results[k]["scores"].get(m, 0) for m in RAGAS_METRICS) / len(RAGAS_METRICS)
        for k in present
    }
    best_k     = max(avg_scores, key=avg_scores.__getitem__)
    best_label = labels[keys.index(best_k)]
    print(f"  Best overall: {best_label}  (avg RAGAS = {avg_scores[best_k]:.4f})\n")


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="RAGAS chunk-strategy sweep for the Compliance Agent"
    )
    parser.add_argument("--quick",            action="store_true",
                        help="Use first 8 test cases only (~10 min)")
    parser.add_argument("--parent-child-only", action="store_true",
                        help="Skip flat sweep, run parent-child only (appends to existing results)")
    args = parser.parse_args()

    if not LLM_API_KEY:
        print("ERROR: LLM_API_KEY is not set in .env")
        raise SystemExit(1)

    with open(TEST_CASES_PATH) as f:
        all_cases = json.load(f)
    test_cases = all_cases[:8] if args.quick else all_cases

    excerpts_by_type: dict[str, list[str]] = defaultdict(list)
    for tc in all_cases:
        excerpts_by_type[tc["document_type"]].append(tc["document_excerpt"])
    composite_docs = {
        dt: "\n\n".join(f"[Clause {i + 1}] {ex}" for i, ex in enumerate(exs))
        for dt, exs in excerpts_by_type.items()
    }

    results: dict = {}
    if args.parent_child_only and RESULTS_PATH.exists():
        with open(RESULTS_PATH) as f:
            results = json.load(f)

    if not args.parent_child_only:
        run_flat_sweep(test_cases, composite_docs, results)

    run_parent_child_sweep(test_cases, composite_docs, results)

    with open(RESULTS_PATH, "w") as f:
        json.dump(results, f, indent=2)
    print(f"Results saved → {RESULTS_PATH}\n")

    print_comparison_table(results)


if __name__ == "__main__":
    main()

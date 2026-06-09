"""
Chunk size sweep — RAGAS evaluation at sizes 200, 500, 750, 1000 plus parent-child.

Uses Groq (llama-3.3-70b-versatile) + Nomic embeddings. No OpenAI key needed.
Each document type's excerpts are merged into one composite document so that
different chunk sizes produce meaningfully different retrieval results.

Usage:
  python chunk_sweep.py               # flat sweep + parent-child
  python chunk_sweep.py --quick       # first 8 test cases only
  python chunk_sweep.py --parent-child  # parent-child only (appends to existing results)

Output: chunk_sweep_results.json + printed comparison table
"""

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

LLM_API_KEY      = os.getenv("LLM_API_KEY", "")
LLM_BASE_URL     = os.getenv("LLM_BASE_URL", "https://api.groq.com/openai/v1")
LLM_MODEL        = os.getenv("LLM_MODEL", "llama-3.3-70b-versatile")
EMBED_API_KEY    = os.getenv("EMBEDDING_API_KEY", "")
EMBED_BASE_URL   = os.getenv("EMBEDDING_BASE_URL", "https://api-atlas.nomic.ai/v1")
EMBED_MODEL      = os.getenv("EMBEDDING_MODEL", "nomic-embed-text-v1.5")

CHUNK_SIZES  = [200, 500, 750, 1000]
TOP_K        = 3
RAGAS_METRICS = ("faithfulness", "answer_relevancy", "context_precision")
TEST_CASES_PATH  = Path(__file__).parent / "test_cases.json"
RESULTS_PATH     = Path(__file__).parent / "chunk_sweep_results.json"


# ── embedding & LLM helpers ───────────────────────────────────────────────────

def embed_batch(texts: list[str], task_type: str = "search_document") -> list[list[float]]:
    # Nomic Atlas native API — not OpenAI-compatible (/embedding/text, uses "texts" not "input")
    resp = requests.post(
        f"{EMBED_BASE_URL}/embedding/text",
        headers={"Authorization": f"Bearer {EMBED_API_KEY}", "Content-Type": "application/json"},
        json={"model": EMBED_MODEL, "texts": texts, "task_type": task_type},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["embeddings"]


def _llm_chat(messages: list[dict], max_tokens: int = 300, retries: int = 4) -> str:
    """Call Groq chat completions with exponential backoff on 429."""
    for attempt in range(retries):
        try:
            resp = requests.post(
                f"{LLM_BASE_URL}/chat/completions",
                headers={"Authorization": f"Bearer {LLM_API_KEY}", "Content-Type": "application/json"},
                json={"model": LLM_MODEL, "messages": messages,
                      "temperature": 0.1, "max_tokens": max_tokens},
                timeout=45,
            )
            resp.raise_for_status()
            return resp.json()["choices"][0]["message"]["content"].strip()
        except requests.exceptions.HTTPError as e:
            if e.response is not None and e.response.status_code == 429 and attempt < retries - 1:
                wait = 2.0 * (2 ** attempt)
                print(f"    rate-limited, waiting {wait:.0f}s...")
                time.sleep(wait)
            else:
                raise


def generate_answer(question: str, context: str) -> str:
    return _llm_chat([
        {"role": "system", "content": (
            "You are a legal compliance expert. "
            "Answer questions using only the provided context. Be concise and accurate."
        )},
        {"role": "user", "content": f"Context:\n{context}\n\nQuestion: {question}"},
    ])


# ── chunking ──────────────────────────────────────────────────────────────────

def chunk_text(text: str, chunk_size: int, overlap: int) -> list[str]:
    """Sliding-window character chunker with overlap."""
    chunks, start = [], 0
    while start < len(text):
        end = min(start + chunk_size, len(text))
        c = text[start:end].strip()
        if c:
            chunks.append(c)
        if end == len(text):
            break
        start += chunk_size - overlap
    return chunks


# ── retrieval ─────────────────────────────────────────────────────────────────

def cosine_sim(a: list[float], b: list[float]) -> float:
    va, vb = np.array(a), np.array(b)
    denom = np.linalg.norm(va) * np.linalg.norm(vb)
    return float(np.dot(va, vb) / (denom + 1e-9))


def retrieve_top_k(
    query_emb: list[float],
    chunk_embs: list[list[float]],
    chunks: list[str],
    k: int,
) -> list[str]:
    scored = sorted(
        enumerate(chunk_embs), key=lambda x: cosine_sim(query_emb, x[1]), reverse=True
    )
    return [chunks[i] for i, _ in scored[:k]]


# ── RAGAS-equivalent metrics (manual, no RAGAS library) ───────────────────────
#
# faithfulness      — fraction of answer sentences supported by the context (LLM judge)
# answer_relevancy  — cosine similarity between question and answer embeddings
# context_precision — fraction of retrieved chunks relevant to the question (LLM judge)

def _llm_yes_no(prompt: str) -> bool:
    text = _llm_chat([{"role": "user", "content": prompt}], max_tokens=5).lower()
    return text.startswith("yes")


def score_faithfulness(answer: str, contexts: list[str]) -> float:
    sentences = [s.strip() for s in answer.replace(".\n", ". ").split(". ") if len(s.strip()) > 10]
    if not sentences:
        return 0.0
    context_blob = "\n".join(contexts)
    supported = 0
    for sent in sentences:
        prompt = (
            f"Context:\n{context_blob}\n\n"
            f'Statement: "{sent}"\n\n'
            "Does this statement follow from the context? Reply only Yes or No."
        )
        try:
            if _llm_yes_no(prompt):
                supported += 1
            time.sleep(0.2)
        except Exception:
            pass
    return supported / len(sentences)


def score_answer_relevancy(question: str, answer: str) -> float:
    try:
        q_emb = embed_batch([question], task_type="search_query")[0]
        a_emb = embed_batch([answer],   task_type="search_document")[0]
        return cosine_sim(q_emb, a_emb)
    except Exception:
        return 0.0


def score_context_precision(question: str, contexts: list[str]) -> float:
    if not contexts:
        return 0.0
    relevant = 0
    for ctx in contexts:
        prompt = (
            f'Question: "{question}"\n\n'
            f"Context chunk:\n{ctx}\n\n"
            "Is this context chunk useful for answering the question? Reply only Yes or No."
        )
        try:
            if _llm_yes_no(prompt):
                relevant += 1
            time.sleep(0.2)
        except Exception:
            pass
    return relevant / len(contexts)


def run_ragas(rag_data: list[dict]) -> dict:
    valid = [d for d in rag_data if d.get("contexts") and d.get("answer")]
    if not valid:
        print("  No valid rows.")
        return {m: 0.0 for m in RAGAS_METRICS} | {"evaluated_count": 0}

    faith_scores, rel_scores, prec_scores = [], [], []
    for d in valid:
        faith_scores.append(score_faithfulness(d["answer"], d["contexts"]))
        rel_scores.append(score_answer_relevancy(d["question"], d["answer"]))
        prec_scores.append(score_context_precision(d["question"], d["contexts"]))

    return {
        "faithfulness":      float(np.mean(faith_scores)),
        "answer_relevancy":  float(np.mean(rel_scores)),
        "context_precision": float(np.mean(prec_scores)),
        "evaluated_count":   len(valid),
    }


# ── parent-child retrieval ────────────────────────────────────────────────────

PARENT_CHUNK_SIZE = 600
CHILD_CHUNK_SIZE  = 150


def build_parent_child_index(doc: str) -> tuple[list[str], list[str], list[list[float]]]:
    """Returns (parent_chunks, child_chunks, child_embeddings)."""
    parent_overlap = max(1, PARENT_CHUNK_SIZE // 10)
    child_overlap  = max(1, CHILD_CHUNK_SIZE  // 5)

    parents = chunk_text(doc, PARENT_CHUNK_SIZE, parent_overlap)
    children, child_to_parent = [], []
    for pid, parent in enumerate(parents):
        for child in chunk_text(parent, CHILD_CHUNK_SIZE, child_overlap):
            children.append(child)
            child_to_parent.append(pid)

    child_embs = embed_batch(children)
    return parents, children, child_embs, child_to_parent


def retrieve_parent_child(
    query_emb: list[float],
    parents: list[str],
    child_embs: list[list[float]],
    child_to_parent: list[int],
    top_k: int,
) -> list[str]:
    """Retrieve by child similarity, return unique parent chunks."""
    scored = sorted(
        range(len(child_embs)),
        key=lambda i: cosine_sim(query_emb, child_embs[i]),
        reverse=True,
    )
    seen, result = set(), []
    for idx in scored:
        pid = child_to_parent[idx]
        if pid not in seen:
            seen.add(pid)
            result.append(parents[pid])
        if len(result) == top_k:
            break
    return result


# ── main ──────────────────────────────────────────────────────────────────────

def run_flat_sweep(test_cases, composite_docs, all_results):
    for chunk_size in CHUNK_SIZES:
        overlap = max(1, chunk_size // 10)
        print(f"\n{'='*60}")
        print(f"Flat  chunk_size={chunk_size}  overlap={overlap}")
        print(f"{'='*60}")

        doc_chunks: dict[str, list[str]] = {}
        doc_embs:   dict[str, list[list[float]]] = {}
        for dt, doc in composite_docs.items():
            chunks = chunk_text(doc, chunk_size, overlap)
            doc_chunks[dt] = chunks
            print(f"  {dt}: {len(chunks)} chunks")
            doc_embs[dt] = embed_batch(chunks)
            time.sleep(0.3)

        rag_data: list[dict] = []
        for i, tc in enumerate(test_cases):
            print(f"  [{i+1:02}/{len(test_cases)}] {tc['id']}: {tc['question'][:55]}...")
            dt = tc["document_type"]
            try:
                q_emb      = embed_batch([tc["question"]], task_type="search_query")[0]
                top_chunks = retrieve_top_k(q_emb, doc_embs[dt], doc_chunks[dt], TOP_K)
                answer     = generate_answer(tc["question"], "\n---\n".join(top_chunks))
                rag_data.append({"id": tc["id"], "question": tc["question"],
                                  "answer": answer, "contexts": top_chunks,
                                  "ground_truth": tc["ground_truth"]})
            except Exception as e:
                print(f"    ERROR: {e}")
                rag_data.append({"id": tc["id"], "question": tc["question"],
                                  "answer": "", "contexts": [],
                                  "ground_truth": tc["ground_truth"], "error": str(e)})
            time.sleep(2.0)   # stay within Groq free-tier (30 req/min)

        print(f"\n  Running RAGAS for chunk_size={chunk_size}...")
        scores = run_ragas(rag_data)
        for m in RAGAS_METRICS:
            print(f"    {m:<22}: {scores.get(m, 0):.4f}")

        all_results[f"flat_{chunk_size}"] = {
            "strategy": "flat", "chunk_size": chunk_size, "overlap": overlap,
            "scores": scores, "test_cases": rag_data,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }


def run_parent_child_sweep(test_cases, composite_docs, all_results):
    print(f"\n{'='*60}")
    print(f"Parent-child  parent={PARENT_CHUNK_SIZE}  child={CHILD_CHUNK_SIZE}")
    print(f"{'='*60}")

    pc_index: dict[str, tuple] = {}
    for dt, doc in composite_docs.items():
        parents, children, child_embs, c2p = build_parent_child_index(doc)
        pc_index[dt] = (parents, child_embs, c2p)
        print(f"  {dt}: {len(parents)} parents → {len(children)} children")
        time.sleep(0.3)

    rag_data: list[dict] = []
    for i, tc in enumerate(test_cases):
        print(f"  [{i+1:02}/{len(test_cases)}] {tc['id']}: {tc['question'][:55]}...")
        dt = tc["document_type"]
        parents, child_embs, c2p = pc_index[dt]
        try:
            q_emb      = embed_batch([tc["question"]], task_type="search_query")[0]
            top_chunks = retrieve_parent_child(q_emb, parents, child_embs, c2p, TOP_K)
            answer     = generate_answer(tc["question"], "\n---\n".join(top_chunks))
            rag_data.append({"id": tc["id"], "question": tc["question"],
                              "answer": answer, "contexts": top_chunks,
                              "ground_truth": tc["ground_truth"]})
        except Exception as e:
            print(f"    ERROR: {e}")
            rag_data.append({"id": tc["id"], "question": tc["question"],
                              "answer": "", "contexts": [],
                              "ground_truth": tc["ground_truth"], "error": str(e)})
        time.sleep(2.0)   # stay within Groq free-tier (30 req/min)

    print("\n  Running RAGAS for parent-child...")
    scores = run_ragas(rag_data)
    for m in RAGAS_METRICS:
        print(f"    {m:<22}: {scores.get(m, 0):.4f}")

    all_results["parent_child"] = {
        "strategy": "parent_child",
        "parent_chunk_size": PARENT_CHUNK_SIZE, "child_chunk_size": CHILD_CHUNK_SIZE,
        "scores": scores, "test_cases": rag_data,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


def print_comparison_table(all_results: dict):
    keys   = [f"flat_{cs}" for cs in CHUNK_SIZES] + ["parent_child"]
    labels = [str(cs) for cs in CHUNK_SIZES] + [f"PC({CHILD_CHUNK_SIZE}→{PARENT_CHUNK_SIZE})"]
    col_w  = 14

    print("\n" + "=" * (27 + col_w * len(keys)))
    print("Chunking Strategy Comparison — RAGAS Scores")
    print("=" * (27 + col_w * len(keys)))
    header = f"  {'Metric':<23}" + "".join(f"{lbl:>{col_w}}" for lbl in labels)
    print(header)
    print("-" * (27 + col_w * len(keys)))

    for m in RAGAS_METRICS:
        vals = [all_results[k]["scores"].get(m, 0.0) for k in keys if k in all_results]
        best = max(vals) if vals else 0
        row  = f"  {m.replace('_', ' ').title():<23}"
        for k in keys:
            v = all_results.get(k, {}).get("scores", {}).get(m, 0.0)
            marker = " *" if v == best else "  "
            row += f"{v:>{col_w - 2}.4f}{marker}"
        print(row)

    print("=" * (27 + col_w * len(keys)))
    print("  * = best score for that metric\n")

    present = [k for k in keys if k in all_results]
    avg_scores = {
        k: sum(all_results[k]["scores"].get(m, 0) for m in RAGAS_METRICS) / len(RAGAS_METRICS)
        for k in present
    }
    best_k = max(avg_scores, key=avg_scores.__getitem__)
    best_label = labels[keys.index(best_k)] if best_k in keys else best_k
    print(f"  Best overall: {best_label}  (avg {avg_scores[best_k]:.4f})\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--quick",        action="store_true", help="Use first 8 test cases only")
    parser.add_argument("--parent-child", action="store_true", help="Run parent-child strategy only")
    parser.add_argument("--all",          action="store_true", help="Run flat sweep + parent-child (default)")
    args = parser.parse_args()

    with open(TEST_CASES_PATH) as f:
        all_cases = json.load(f)
    test_cases = all_cases[:8] if args.quick else all_cases

    excerpts_by_type: dict[str, list[str]] = defaultdict(list)
    for tc in all_cases:
        excerpts_by_type[tc["document_type"]].append(tc["document_excerpt"])
    composite_docs = {
        dt: "\n\n".join(f"[Clause {i+1}] {ex}" for i, ex in enumerate(exs))
        for dt, exs in excerpts_by_type.items()
    }

    # Load previous results if they exist (so we can append parent-child to a prior flat sweep)
    all_results: dict = {}
    if RESULTS_PATH.exists():
        with open(RESULTS_PATH) as f:
            all_results = json.load(f)

    run_flat   = not args.parent_child
    run_pc     = args.parent_child or args.all or not args.parent_child  # default: run both

    if run_flat:
        run_flat_sweep(test_cases, composite_docs, all_results)
    if run_pc:
        run_parent_child_sweep(test_cases, composite_docs, all_results)

    with open(RESULTS_PATH, "w") as f:
        json.dump(all_results, f, indent=2)
    print(f"Results saved to {RESULTS_PATH}")

    print_comparison_table(all_results)


if __name__ == "__main__":
    main()

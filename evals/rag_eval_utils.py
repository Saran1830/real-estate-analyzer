"""
rag_eval_utils.py — shared RAGAS evaluation utilities for the Compliance Agent.

IMPORTANT: import this module before any ragas import. It applies a compatibility
shim for RAGAS 0.2.x + langchain-community>=0.3, where
langchain_community.chat_models.vertexai was removed but RAGAS still imports it
unconditionally at module load time.
"""

import sys
import types

# ── RAGAS 0.2.x / langchain-community compatibility ──────────────────────────
# ragas.llms.base does `from langchain_community.chat_models.vertexai import
# ChatVertexAI` at import time. In langchain-community>=0.3 that module was
# removed (ChatVertexAI moved to langchain-google-vertexai). We stub it here so
# the import succeeds without installing the full Google Cloud SDK.
_VERTEXAI_COMPAT_PATH = "langchain_community.chat_models.vertexai"
if _VERTEXAI_COMPAT_PATH not in sys.modules:
    _stub = types.ModuleType(_VERTEXAI_COMPAT_PATH)
    _stub.ChatVertexAI = type("ChatVertexAI", (), {})
    sys.modules[_VERTEXAI_COMPAT_PATH] = _stub
# ─────────────────────────────────────────────────────────────────────────────

import requests
from langchain_core.embeddings import Embeddings
from langchain_openai import ChatOpenAI


class NomicEmbeddings(Embeddings):
    """
    LangChain-compatible wrapper for the Nomic Atlas embedding API.

    Nomic's REST API uses the endpoint /v1/embedding/text with a ``texts``
    field, which differs from the OpenAI /v1/embeddings format that
    ``OpenAIEmbeddings`` expects. This class translates between the two so
    RAGAS (which calls LangChain's Embeddings interface) can use Nomic without
    an OpenAI key.
    """

    def __init__(self, api_key: str, base_url: str, model: str) -> None:
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._model = model

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return self._call(texts, "search_document")

    def embed_query(self, text: str) -> list[float]:
        return self._call([text], "search_query")[0]

    def _call(self, texts: list[str], task_type: str) -> list[list[float]]:
        resp = requests.post(
            f"{self._base_url}/embedding/text",
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
            json={"model": self._model, "texts": texts, "task_type": task_type},
            timeout=60,
        )
        resp.raise_for_status()
        return resp.json()["embeddings"]


def safe_ragas_score(val) -> float:
    """
    RAGAS 0.2.x returns a float when all samples succeed and a list when some
    fail (e.g. due to rate-limit errors). This helper normalises both cases to
    a single float — mean of non-NaN values — so callers don't need to branch.
    """
    import math
    if isinstance(val, (int, float)):
        return float(val)
    if isinstance(val, list):
        nums = [float(v) for v in val
                if v is not None and not (isinstance(v, float) and math.isnan(v))]
        return float(sum(nums) / len(nums)) if nums else 0.0
    return 0.0


def build_ragas_llm_and_embeddings(
    llm_api_key: str,
    llm_base_url: str,
    llm_model: str,
    embed_api_key: str,
    embed_base_url: str,
    embed_model: str,
) -> tuple:
    """
    Build RAGAS-compatible LLM and embeddings wrappers.

    Uses Groq (or any OpenAI-compatible endpoint) for the judge LLM and Nomic
    Atlas for embeddings. Returns ``(LangchainLLMWrapper, LangchainEmbeddingsWrapper)``
    ready to pass to ``ragas.evaluate()``.
    """
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    llm_kwargs: dict = {"api_key": llm_api_key, "model": llm_model, "temperature": 0}
    if llm_base_url:
        llm_kwargs["base_url"] = llm_base_url

    ragas_llm = LangchainLLMWrapper(ChatOpenAI(**llm_kwargs))
    ragas_emb = LangchainEmbeddingsWrapper(
        NomicEmbeddings(api_key=embed_api_key, base_url=embed_base_url, model=embed_model)
    )
    return ragas_llm, ragas_emb

"use client";

import { useState, useRef, useEffect } from "react";
import { askQuestion } from "@/lib/api";
import type { ConversationTurn } from "@/lib/types";
import { RiskBadge } from "./RiskBadge";
import { SourceChunk } from "./SourceChunk";

export function QAPanel({ sessionId }: { sessionId: string }) {
  const [turns, setTurns] = useState<ConversationTurn[]>([]);
  const [question, setQuestion] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [turns]);

  async function handleAsk() {
    if (!question.trim() || loading) return;
    const q = question.trim();
    setQuestion("");
    setLoading(true);
    setError(null);

    try {
      const res = await askQuestion(sessionId, q);
      setTurns((prev) => [
        ...prev,
        {
          question: q,
          answer: res.answer,
          confidence: res.confidence,
          sources: res.sources,
          rerankScores: res.rerankScores,
        },
      ]);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Request failed");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <h2 className="section-title">Follow-up Q&A</h2>

      {turns.length > 0 && (
        <div className="flex flex-col gap-5 max-h-[540px] overflow-y-auto pr-1">
          {turns.map((turn, i) => (
            <div key={i} className="flex flex-col gap-3">
              {/* User question */}
              <div className="flex justify-end">
                <div className="bg-indigo-600/20 border border-indigo-600/30 rounded-2xl rounded-tr-sm px-4 py-2.5 max-w-[85%]">
                  <p className="text-sm text-gray-200">{turn.question}</p>
                </div>
              </div>

              {/* Assistant answer */}
              <div className="flex flex-col gap-2">
                <div className="bg-gray-800 border border-gray-700 rounded-2xl rounded-tl-sm px-4 py-3 max-w-[90%]">
                  <p className="text-sm text-gray-200 leading-relaxed whitespace-pre-wrap">
                    {turn.answer}
                  </p>
                  <div className="mt-2 flex items-center gap-1.5 text-xs text-gray-500">
                    <span>Confidence:</span>
                    <RiskBadge level={turn.confidence} />
                  </div>
                </div>

                {/* Sources */}
                {turn.sources.length > 0 && (
                  <details className="ml-1">
                    <summary className="text-xs text-gray-500 cursor-pointer hover:text-gray-400 select-none">
                      {turn.sources.length} source chunks · click to expand
                    </summary>
                    <div className="mt-2 grid grid-cols-1 sm:grid-cols-2 gap-2">
                      {turn.sources.map((src, j) => (
                        <SourceChunk key={j} chunk={src} rank={j + 1} />
                      ))}
                    </div>
                  </details>
                )}
              </div>
            </div>
          ))}
          <div ref={bottomRef} />
        </div>
      )}

      {turns.length === 0 && (
        <p className="text-sm text-gray-600">
          Ask a follow-up question about the document — answers reference previous context
          automatically.
        </p>
      )}

      {error && (
        <p className="text-sm text-red-400 bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2">
          {error}
        </p>
      )}

      <div className="flex gap-2">
        <input
          className="input flex-1"
          placeholder="e.g. What happens if the buyer defaults?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && !e.shiftKey && handleAsk()}
          disabled={loading}
        />
        <button
          onClick={handleAsk}
          disabled={!question.trim() || loading}
          className="btn-primary shrink-0"
        >
          {loading ? "…" : "Ask"}
        </button>
      </div>
    </div>
  );
}

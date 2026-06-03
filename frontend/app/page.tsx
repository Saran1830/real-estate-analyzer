"use client";

import { useState } from "react";
import { analyzeDocument, clearSession } from "@/lib/api";
import type { AnalyzeResponse, RiskLevel } from "@/lib/types";
import { DOCUMENT_TYPES } from "@/lib/types";
import { SAMPLE_CONTRACTS } from "@/lib/sampleContracts";
import { RiskBadge } from "@/components/RiskBadge";
import { ClauseList } from "@/components/ClauseList";
import { QAPanel } from "@/components/QAPanel";
import { AgentTrace } from "@/components/AgentTrace";

const riskSummaryColor: Record<RiskLevel, string> = {
  HIGH: "border-red-500/40 bg-red-500/5",
  MEDIUM: "border-amber-500/40 bg-amber-500/5",
  LOW: "border-green-500/40 bg-green-500/5",
  BLOCKED: "border-gray-600 bg-gray-800/40",
  UNKNOWN: "border-gray-600 bg-gray-800/40",
};

export default function Home() {
  const [documentText, setDocumentText] = useState("");
  const [documentType, setDocumentType] = useState(DOCUMENT_TYPES[0].value);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<AnalyzeResponse | null>(null);

  async function handleAnalyze() {
    if (!documentText.trim() || loading) return;
    if (result) {
      await clearSession(result.sessionId).catch(() => {});
    }
    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const res = await analyzeDocument(documentText, documentType);
      setResult(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Analysis failed");
    } finally {
      setLoading(false);
    }
  }

  function loadSample(idx: number) {
    const s = SAMPLE_CONTRACTS[idx];
    setDocumentText(s.text);
    setDocumentType(s.documentType);
    setResult(null);
    setError(null);
  }

  return (
    <main className="min-h-screen px-4 py-10 max-w-6xl mx-auto flex flex-col gap-8">
      {/* Header */}
      <div className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold text-gray-100 tracking-tight">
          Compliance Agent
        </h1>
        <p className="text-gray-500 text-sm">
          Real estate document review · Two-stage RAG · Conversation memory
        </p>
      </div>

      {/* Input section */}
      <div className="card flex flex-col gap-4">
        {/* Document type + samples row */}
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex-1 min-w-[200px]">
            <label className="label">Document type</label>
            <select
              value={documentType}
              onChange={(e) => setDocumentType(e.target.value)}
              className="input"
            >
              {DOCUMENT_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </div>

          <div className="flex gap-2 flex-wrap">
            {SAMPLE_CONTRACTS.map((s, i) => (
              <button key={i} onClick={() => loadSample(i)} className="btn-ghost text-sm">
                Load: {s.label}
              </button>
            ))}
          </div>
        </div>

        {/* Document textarea */}
        <div>
          <label className="label">Document text</label>
          <textarea
            className="input h-52 resize-none font-mono text-xs leading-relaxed"
            placeholder="Paste the full contract text here…"
            value={documentText}
            onChange={(e) => setDocumentText(e.target.value)}
          />
          <p className="mt-1 text-xs text-gray-600">{documentText.length.toLocaleString()} characters</p>
        </div>

        <button
          onClick={handleAnalyze}
          disabled={!documentText.trim() || loading}
          className="btn-primary self-start"
        >
          {loading ? "Analyzing…" : "Analyze Document"}
        </button>

        {error && (
          <p className="text-sm text-red-400 bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2">
            {error}
          </p>
        )}
      </div>

      {/* Results */}
      {result && (
        <div className="flex flex-col gap-6">
          {/* Risk summary banner */}
          <div className={`card border-2 ${riskSummaryColor[result.riskLevel]} flex flex-col gap-2`}>
            <div className="flex items-center gap-3">
              <RiskBadge level={result.riskLevel} size="lg" />
              <span className="text-sm text-gray-400">
                Session: <code className="font-mono text-xs text-gray-500">{result.sessionId}</code>
              </span>
            </div>
            <p className="text-gray-300 text-sm leading-relaxed">{result.summary}</p>
          </div>

          {/* Two-column results */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            {/* Findings */}
            <div className="flex flex-col gap-3">
              <h2 className="section-title">
                Findings
                <span className="ml-2 text-sm font-normal text-gray-500">
                  {result.findings.length} clauses flagged
                </span>
              </h2>
              <ClauseList findings={result.findings} />
            </div>

            {/* Q&A */}
            <div className="flex flex-col gap-3">
              <div className="card flex flex-col gap-4">
                <QAPanel sessionId={result.sessionId} />
              </div>
            </div>
          </div>

          {/* Agent trace */}
          <AgentTrace trace={result.agentTrace} />
        </div>
      )}
    </main>
  );
}

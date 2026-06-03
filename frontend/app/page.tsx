"use client";

import { useState } from "react";
import { analyzeDocument, clearSession } from "@/lib/api";
import type { AnalyzeResponse, RiskLevel } from "@/lib/types";
import { DOCUMENT_TYPES } from "@/lib/types";
import { RiskBadge } from "@/components/RiskBadge";
import { ClauseList } from "@/components/ClauseList";
import { QAPanel } from "@/components/QAPanel";
import { AgentTrace } from "@/components/AgentTrace";
import { FileUpload } from "@/components/FileUpload";
import { DealAnalyzer } from "@/components/DealAnalyzer";

const riskSummaryColor: Record<RiskLevel, string> = {
  HIGH: "border-red-500/40 bg-red-500/5",
  MEDIUM: "border-amber-500/40 bg-amber-500/5",
  LOW: "border-green-500/40 bg-green-500/5",
  BLOCKED: "border-gray-600 bg-gray-800/40",
  UNKNOWN: "border-gray-600 bg-gray-800/40",
};

type Tab = "compliance" | "deal";

export default function Home() {
  const [activeTab, setActiveTab] = useState<Tab>("compliance");
  const [documentText, setDocumentText] = useState("");
  const [documentType, setDocumentType] = useState<string>(DOCUMENT_TYPES[0].value);
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

  return (
    <main className="min-h-screen px-4 py-10 max-w-6xl mx-auto flex flex-col gap-8">
      {/* Header */}
      <div className="flex flex-col gap-1">
        <h1 className="text-3xl font-bold text-gray-100 tracking-tight">
          AI Real Estate Agent
        </h1>
        <p className="text-gray-500 text-sm">
          Contract compliance · Deal analysis · Two-stage RAG · Conversation memory
        </p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-800">
        <button
          onClick={() => setActiveTab("compliance")}
          className={`px-4 py-2 text-sm font-medium transition-colors ${
            activeTab === "compliance"
              ? "text-gray-100 border-b-2 border-blue-500 -mb-px"
              : "text-gray-500 hover:text-gray-300"
          }`}
        >
          Compliance Analyzer
        </button>
        <button
          onClick={() => setActiveTab("deal")}
          className={`px-4 py-2 text-sm font-medium transition-colors flex items-center gap-2 ${
            activeTab === "deal"
              ? "text-gray-100 border-b-2 border-blue-500 -mb-px"
              : "text-gray-500 hover:text-gray-300"
          }`}
        >
          Deal Analyzer
        </button>
      </div>

      {/* Compliance tab */}
      {activeTab === "compliance" && (
        <>
          <div className="card flex flex-col gap-4">
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
            </div>

            <div>
              <div className="flex items-center justify-between mb-1">
                <label className="label">Document text</label>
                <FileUpload onTextExtracted={(text) => { setDocumentText(text); setResult(null); setError(null); }} />
              </div>
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

          {result && (
            <div className="flex flex-col gap-6">
              <div className={`card border-2 ${riskSummaryColor[result.riskLevel]} flex flex-col gap-2`}>
                <div className="flex items-center gap-3">
                  <RiskBadge level={result.riskLevel} size="lg" />
                  <span className="text-sm text-gray-400">
                    Session: <code className="font-mono text-xs text-gray-500">{result.sessionId}</code>
                  </span>
                </div>
                <p className="text-gray-300 text-sm leading-relaxed">{result.summary}</p>
              </div>

              <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                <div className="flex flex-col gap-3">
                  <h2 className="section-title">
                    Findings
                    <span className="ml-2 text-sm font-normal text-gray-500">
                      {result.findings.length} clauses flagged
                    </span>
                  </h2>
                  <ClauseList findings={result.findings} />
                </div>

                <div className="flex flex-col gap-3">
                  <div className="card flex flex-col gap-4">
                    <QAPanel sessionId={result.sessionId} />
                  </div>
                </div>
              </div>

              <AgentTrace trace={result.agentTrace} />
            </div>
          )}
        </>
      )}

      {/* Deal Analyzer tab */}
      {activeTab === "deal" && <DealAnalyzer />}
    </main>
  );
}

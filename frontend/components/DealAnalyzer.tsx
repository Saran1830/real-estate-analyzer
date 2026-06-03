"use client";

import { useState } from "react";
import { analyzeDeal, clearSession, type DealDocumentPayload } from "@/lib/api";
import type { DealAnalysisResponse, DealVerdict } from "@/lib/types";
import { DEAL_DOCUMENT_TYPES } from "@/lib/types";
import { FileUpload } from "@/components/FileUpload";
import { QAPanel } from "@/components/QAPanel";
import { AgentTrace } from "@/components/AgentTrace";

// ── Verdict config ─────────────────────────────────────────────────────────────

const VERDICT_CONFIG: Record<DealVerdict, { label: string; color: string; dot: string }> = {
  STRONG_BUY: { label: "Strong Buy", color: "border-green-500/40 bg-green-500/5 text-green-400", dot: "bg-green-400" },
  BUY:        { label: "Buy",         color: "border-blue-500/40 bg-blue-500/5 text-blue-400",   dot: "bg-blue-400" },
  MARGINAL:   { label: "Marginal",    color: "border-amber-500/40 bg-amber-500/5 text-amber-400", dot: "bg-amber-400" },
  PASS:       { label: "Pass",        color: "border-red-500/40 bg-red-500/5 text-red-400",      dot: "bg-red-400" },
  UNKNOWN:    { label: "Unknown",     color: "border-gray-600 bg-gray-800/40 text-gray-400",     dot: "bg-gray-400" },
};

const STRATEGY_LABEL: Record<string, string> = {
  FIX_AND_FLIP: "Fix & Flip",
  RENTAL:       "Rental / Buy & Hold",
  WHOLESALE:    "Wholesale",
  UNKNOWN:      "Unknown strategy",
};

const FRAMEWORK_LABEL: Record<string, string> = {
  "70_PERCENT_RULE": "70% Rule",
  CAP_RATE:          "Cap Rate",
  CASH_ON_CASH:      "Cash-on-Cash",
  QUALITATIVE:       "Qualitative",
};

// ── Helpers ────────────────────────────────────────────────────────────────────

function fmt(value: number | null | undefined, prefix = "$"): string {
  if (value == null) return "—";
  return prefix + value.toLocaleString("en-US", { maximumFractionDigits: 0 });
}

// ── Uploaded doc row ───────────────────────────────────────────────────────────

interface UploadedDoc extends DealDocumentPayload {
  id: string;
}

// ── Component ─────────────────────────────────────────────────────────────────

export function DealAnalyzer() {
  const [docs, setDocs] = useState<UploadedDoc[]>([]);
  const [address, setAddress] = useState("");
  const [askingPrice, setAskingPrice] = useState("");
  const [repairs, setRepairs] = useState("");
  const [notes, setNotes] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<DealAnalysisResponse | null>(null);

  function addDoc(text: string, filename?: string) {
    const id = crypto.randomUUID();
    setDocs((prev) => [...prev, { id, name: filename ?? "Document", text, type: "Other" }]);
  }

  function removeDoc(id: string) {
    setDocs((prev) => prev.filter((d) => d.id !== id));
  }

  function updateDocType(id: string, type: string) {
    setDocs((prev) => prev.map((d) => (d.id === id ? { ...d, type } : d)));
  }

  async function handleAnalyze() {
    if (docs.length === 0 || loading) return;
    if (result) await clearSession(result.sessionId).catch(() => {});
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await analyzeDeal(
        docs.map(({ name, text, type }) => ({ name, text, type })),
        address || undefined,
        askingPrice ? parseFloat(askingPrice) : undefined,
        repairs ? parseFloat(repairs) : undefined,
        notes || undefined
      );
      setResult(res);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Analysis failed");
    } finally {
      setLoading(false);
    }
  }

  const verdict = result ? (VERDICT_CONFIG[result.verdict] ?? VERDICT_CONFIG.UNKNOWN) : null;

  return (
    <div className="flex flex-col gap-6">

      {/* Documents */}
      <div className="card flex flex-col gap-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-semibold text-gray-200">Deal Documents</h2>
            <p className="text-xs text-gray-500 mt-0.5">Upload all files relevant to this deal</p>
          </div>
          <FileUpload onTextExtracted={addDoc} label="+ Add Document" />
        </div>

        {docs.length === 0 ? (
          <p className="text-xs text-gray-600 italic">No documents uploaded yet</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {docs.map((doc) => (
              <li key={doc.id} className="flex items-center gap-2 bg-gray-800/40 rounded-lg px-3 py-2">
                <span className="text-xs text-gray-300 flex-1 truncate">{doc.name}</span>
                <select
                  value={doc.type}
                  onChange={(e) => updateDocType(doc.id, e.target.value)}
                  className="text-xs bg-gray-900 border border-gray-700 rounded px-2 py-1 text-gray-400"
                >
                  {DEAL_DOCUMENT_TYPES.map((t) => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
                <button
                  onClick={() => removeDoc(doc.id)}
                  className="text-gray-600 hover:text-red-400 text-xs px-1"
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Deal context */}
      <div className="card flex flex-col gap-4">
        <div>
          <h2 className="text-sm font-semibold text-gray-200">Deal Context
            <span className="ml-2 text-xs font-normal text-gray-600">optional</span>
          </h2>
          <p className="text-xs text-gray-500 mt-0.5">Provide what you know — AI will infer the rest from your documents</p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div className="sm:col-span-2">
            <label className="label">Property address</label>
            <input
              type="text"
              className="input"
              placeholder="123 Oak St, Dallas, TX 75201"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
          </div>
          <div>
            <label className="label">Asking price ($)</label>
            <input
              type="number"
              className="input"
              placeholder="185000"
              value={askingPrice}
              onChange={(e) => setAskingPrice(e.target.value)}
            />
          </div>
          <div>
            <label className="label">Estimated repairs ($)</label>
            <input
              type="number"
              className="input"
              placeholder="35000"
              value={repairs}
              onChange={(e) => setRepairs(e.target.value)}
            />
          </div>
          <div className="sm:col-span-2">
            <label className="label">Additional notes</label>
            <textarea
              className="input h-20 resize-none text-xs"
              placeholder="e.g. seller is motivated, property has foundation issues, comparable sold at $280k last month…"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
            />
          </div>
        </div>

        <button
          onClick={handleAnalyze}
          disabled={docs.length === 0 || loading}
          className="btn-primary self-start"
        >
          {loading ? "Analyzing deal…" : "Analyze Deal"}
        </button>

        {error && (
          <p className="text-sm text-red-400 bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2">
            {error}
          </p>
        )}
      </div>

      {/* Results */}
      {result && verdict && (
        <div className="flex flex-col gap-6">

          {/* Verdict banner */}
          <div className={`card border-2 ${verdict.color} flex flex-col gap-3`}>
            <div className="flex items-center justify-between flex-wrap gap-3">
              <div className="flex items-center gap-3">
                <span className={`w-3 h-3 rounded-full ${verdict.dot}`} />
                <span className="text-lg font-bold">{verdict.label}</span>
                <span className="text-sm text-gray-500">·</span>
                <span className="text-sm text-gray-400">Score: <strong className="text-gray-200">{result.score}/100</strong></span>
              </div>
              <div className="flex gap-2 text-xs">
                <span className="bg-gray-800 text-gray-400 px-2 py-1 rounded-full">
                  {STRATEGY_LABEL[result.strategy] ?? result.strategy}
                </span>
                <span className="bg-gray-800 text-gray-400 px-2 py-1 rounded-full">
                  {FRAMEWORK_LABEL[result.framework] ?? result.framework}
                </span>
              </div>
            </div>
            <p className="text-gray-300 text-sm leading-relaxed">{result.summary}</p>
          </div>

          {/* Score bar */}
          <div className="flex items-center gap-3">
            <span className="text-xs text-gray-600 w-8">0</span>
            <div className="flex-1 h-2 bg-gray-800 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${
                  result.score >= 90 ? "bg-green-500" :
                  result.score >= 70 ? "bg-blue-500" :
                  result.score >= 50 ? "bg-amber-500" : "bg-red-500"
                }`}
                style={{ width: `${result.score}%` }}
              />
            </div>
            <span className="text-xs text-gray-600 w-8 text-right">100</span>
          </div>

          {/* Financials + Risk side by side */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">

            {/* Financials */}
            {result.financials && (
              <div className="card flex flex-col gap-3">
                <h3 className="section-title">Financials</h3>
                <table className="w-full text-sm">
                  <tbody className="divide-y divide-gray-800">
                    {[
                      ["Asking Price",         fmt(result.financials.askingPrice)],
                      ["Estimated Repairs",    fmt(result.financials.estimatedRepairs)],
                      ["Estimated ARV",        fmt(result.financials.estimatedARV)],
                      ["Max Allowable Offer",  fmt(result.financials.maxAllowableOffer)],
                      ["Projected Profit",     fmt(result.financials.projectedProfit)],
                      ["ROI",                  result.financials.roi ?? "—"],
                      ["Projected Rent/mo",    result.financials.projectedMonthlyRent ?? "—"],
                      ["Cap Rate",             result.financials.capRate ?? "—"],
                      ["Cash-on-Cash",         result.financials.cashOnCash ?? "—"],
                    ].filter(([, v]) => v !== "—").map(([label, value]) => (
                      <tr key={label}>
                        <td className="py-2 text-gray-500">{label}</td>
                        <td className="py-2 text-gray-200 text-right font-mono">{value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Risks & flags */}
            <div className="flex flex-col gap-4">
              {result.riskFactors.length > 0 && (
                <div className="card flex flex-col gap-2">
                  <h3 className="section-title">Risk Factors</h3>
                  <ul className="flex flex-col gap-1.5">
                    {result.riskFactors.map((r, i) => (
                      <li key={i} className="flex gap-2 text-sm text-gray-400">
                        <span className="text-amber-500 mt-0.5">▲</span>
                        {r}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {result.complianceFlags.length > 0 && (
                <div className="card flex flex-col gap-2">
                  <h3 className="section-title">Contract Flags</h3>
                  <ul className="flex flex-col gap-1.5">
                    {result.complianceFlags.map((f, i) => (
                      <li key={i} className="flex gap-2 text-sm text-gray-400">
                        <span className="text-red-500 mt-0.5">!</span>
                        {f}
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          </div>

          {/* Market notes */}
          {result.marketNotes && (
            <div className="card flex flex-col gap-2">
              <h3 className="section-title">Market Notes</h3>
              <p className="text-sm text-gray-400 leading-relaxed">{result.marketNotes}</p>
            </div>
          )}

          {/* Recommendation */}
          <div className="card border border-blue-500/20 bg-blue-500/5 flex flex-col gap-2">
            <h3 className="text-sm font-semibold text-blue-400">Recommendation</h3>
            <p className="text-sm text-gray-300 leading-relaxed">{result.recommendation}</p>
          </div>

          {/* Q&A */}
          <div className="card flex flex-col gap-4">
            <QAPanel sessionId={result.sessionId} />
          </div>

          {/* Agent trace */}
          <AgentTrace trace={result.agentTrace} />
        </div>
      )}
    </div>
  );
}

"use client";

import { useState } from "react";
import type { NodeExecution } from "@/lib/types";

const statusColor: Record<string, string> = {
  OK: "text-green-400",
  VALID: "text-green-400",
  INVALID: "text-red-400",
  ERROR: "text-red-400",
  PARSE_ERROR: "text-amber-400",
};

export function AgentTrace({ trace }: { trace: NodeExecution[] }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="card">
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex items-center justify-between w-full text-left"
      >
        <span className="section-title mb-0">
          Agent Trace
          <span className="ml-2 text-xs font-normal text-gray-500">
            {trace.length} nodes · {trace.reduce((s, n) => s + n.latencyMs, 0)}ms total
          </span>
        </span>
        <span className="text-gray-500 text-sm">{open ? "▲" : "▼"}</span>
      </button>

      {open && (
        <div className="mt-4 flex flex-col gap-2">
          {trace.map((node, i) => (
            <div
              key={i}
              className="flex items-start gap-3 bg-gray-800/60 rounded-lg px-3 py-2.5"
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-mono font-semibold text-gray-200">
                    {node.node}
                  </span>
                  <span className={`text-xs font-semibold ${statusColor[node.status] ?? "text-gray-400"}`}>
                    {node.status}
                  </span>
                </div>
                {node.detail && (
                  <p className="text-xs text-gray-500 mt-0.5 truncate">{node.detail}</p>
                )}
              </div>
              <span className="text-xs text-gray-600 tabular-nums shrink-0">
                {node.latencyMs}ms
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

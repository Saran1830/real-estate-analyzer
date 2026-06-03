import type { Finding } from "@/lib/types";
import { RiskBadge } from "./RiskBadge";

export function FindingCard({ finding }: { finding: Finding }) {
  return (
    <div className="card flex flex-col gap-2">
      <div className="flex items-start justify-between gap-3">
        <p className="font-semibold text-gray-100 leading-snug">{finding.clause}</p>
        <RiskBadge level={finding.risk} />
      </div>
      <p className="text-sm text-gray-400 leading-relaxed">{finding.explanation}</p>
      <div className="flex items-center gap-1.5 text-xs text-gray-600">
        <span>Confidence:</span>
        <RiskBadge level={finding.confidence} />
      </div>
    </div>
  );
}

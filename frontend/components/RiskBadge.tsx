import type { RiskLevel } from "@/lib/types";

const styles: Record<RiskLevel, string> = {
  HIGH: "bg-red-500/15 text-red-400 border-red-500/30",
  MEDIUM: "bg-amber-500/15 text-amber-400 border-amber-500/30",
  LOW: "bg-green-500/15 text-green-400 border-green-500/30",
  BLOCKED: "bg-gray-500/15 text-gray-400 border-gray-500/30",
  UNKNOWN: "bg-gray-500/15 text-gray-400 border-gray-500/30",
};

export function RiskBadge({ level, size = "sm" }: { level: RiskLevel; size?: "sm" | "lg" }) {
  const base = size === "lg"
    ? "px-3 py-1 text-sm font-bold rounded-lg border"
    : "px-2 py-0.5 text-xs font-semibold rounded border";
  return (
    <span className={`inline-flex items-center ${base} ${styles[level]}`}>{level}</span>
  );
}

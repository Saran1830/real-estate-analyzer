import type { Finding } from "@/lib/types";
import { FindingCard } from "./FindingCard";

export function ClauseList({ findings }: { findings: Finding[] }) {
  if (findings.length === 0) {
    return <p className="text-sm text-gray-500">No findings identified.</p>;
  }

  const sorted = [...findings].sort((a, b) => {
    const order = { HIGH: 0, MEDIUM: 1, LOW: 2, BLOCKED: 3, UNKNOWN: 4 };
    return order[a.risk] - order[b.risk];
  });

  return (
    <div className="flex flex-col gap-3">
      {sorted.map((f, i) => (
        <FindingCard key={i} finding={f} />
      ))}
    </div>
  );
}

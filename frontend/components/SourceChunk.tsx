import type { SourceChunk as SourceChunkType } from "@/lib/types";

function ScoreBar({ label, value }: { label: string; value: number }) {
  const pct = Math.round(value * 100);
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="w-16 text-gray-500 shrink-0">{label}</span>
      <div className="flex-1 bg-gray-800 rounded-full h-1.5">
        <div
          className="bg-indigo-500 h-1.5 rounded-full transition-all"
          style={{ width: `${pct}%` }}
        />
      </div>
      <span className="w-8 text-right text-gray-400 tabular-nums">{value.toFixed(2)}</span>
    </div>
  );
}

export function SourceChunk({ chunk, rank }: { chunk: SourceChunkType; rank: number }) {
  return (
    <div className="bg-gray-800/50 border border-gray-700/50 rounded-lg p-3 flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-indigo-400">Source #{rank}</span>
      </div>
      <p className="text-xs text-gray-300 leading-relaxed line-clamp-4">{chunk.text}</p>
      <div className="flex flex-col gap-1 pt-1 border-t border-gray-700/50">
        <ScoreBar label="Cosine" value={chunk.cosineScore} />
        <ScoreBar label="Re-rank" value={chunk.rerankScore} />
      </div>
    </div>
  );
}

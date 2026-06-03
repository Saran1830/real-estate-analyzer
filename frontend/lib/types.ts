export type RiskLevel = "HIGH" | "MEDIUM" | "LOW" | "BLOCKED" | "UNKNOWN";

export interface Finding {
  clause: string;
  risk: RiskLevel;
  explanation: string;
  confidence: RiskLevel;
}

export interface NodeExecution {
  node: string;
  status: string;
  latencyMs: number;
  detail: string;
}

export interface SourceChunk {
  text: string;
  cosineScore: number;
  rerankScore: number;
}

export interface RerankScore {
  originalIndex: number;
  cosineScore: number;
  rerankScore: number;
  excerpt: string;
}

export interface AnalyzeResponse {
  sessionId: string;
  riskLevel: RiskLevel;
  summary: string;
  findings: Finding[];
  agentTrace: NodeExecution[];
}

export interface AskResponse {
  answer: string;
  confidence: RiskLevel;
  sources: SourceChunk[];
  rerankScores: RerankScore[];
  agentTrace: NodeExecution[];
}

export interface ConversationTurn {
  question: string;
  answer: string;
  confidence: RiskLevel;
  sources: SourceChunk[];
  rerankScores: RerankScore[];
}

// ── Deal Analyzer ─────────────────────────────────────────────────────────────

export interface DealFinancials {
  askingPrice: number | null;
  estimatedRepairs: number | null;
  estimatedARV: number | null;
  maxAllowableOffer: number | null;
  projectedProfit: number | null;
  roi: string | null;
  projectedMonthlyRent: string | null;
  capRate: string | null;
  cashOnCash: string | null;
}

export type DealVerdict = "STRONG_BUY" | "BUY" | "MARGINAL" | "PASS" | "UNKNOWN";

export interface DealAnalysisResponse {
  sessionId: string;
  verdict: DealVerdict;
  score: number;
  strategy: string;
  framework: string;
  financials: DealFinancials | null;
  marketNotes: string;
  riskFactors: string[];
  complianceFlags: string[];
  summary: string;
  recommendation: string;
  agentTrace: NodeExecution[];
}

export const DEAL_DOCUMENT_TYPES = [
  "Purchase Agreement",
  "Inspection Report",
  "Comparable Sales (Comps)",
  "Market Report",
  "Title Report",
  "Loan Documents",
  "Property Tax Records",
  "Other",
] as const;

export const DOCUMENT_TYPES = [
  { value: "wholesale_purchase_agreement", label: "Wholesale Purchase Agreement" },
  { value: "loan_agreement", label: "Loan Agreement" },
  { value: "letter_of_intent", label: "Letter of Intent (LOI)" },
  { value: "commercial_sales_agreement", label: "Commercial Sales Agreement" },
  { value: "residential_lease", label: "Residential Lease" },
  { value: "design_construction_agreement", label: "Design & Construction Agreement" },
  { value: "vendor_contract", label: "Vendor Contract" },
  { value: "nda", label: "NDA" },
  { value: "executive_developer_program", label: "Executive Developer Program" },
] as const;

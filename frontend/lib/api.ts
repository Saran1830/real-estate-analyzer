import type { AnalyzeResponse, AskResponse, DealAnalysisResponse } from "./types";

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`${res.status} ${res.statusText}: ${body}`);
  }
  return res.json() as Promise<T>;
}

export async function analyzeDocument(
  documentText: string,
  documentType: string,
  tenantId = "demo"
): Promise<AnalyzeResponse> {
  return request<AnalyzeResponse>("/api/compliance/analyze", {
    method: "POST",
    headers: { "X-Tenant-ID": tenantId },
    body: JSON.stringify({ documentText, documentType }),
  });
}

export async function askQuestion(
  sessionId: string,
  question: string,
  tenantId = "demo"
): Promise<AskResponse> {
  return request<AskResponse>("/api/compliance/ask", {
    method: "POST",
    headers: { "X-Tenant-ID": tenantId },
    body: JSON.stringify({ sessionId, question }),
  });
}

export interface DealDocumentPayload {
  name: string;
  text: string;
  type: string;
}

export async function analyzeDeal(
  documents: DealDocumentPayload[],
  address?: string,
  askingPrice?: number,
  estimatedRepairs?: number,
  notes?: string
): Promise<DealAnalysisResponse> {
  return request<DealAnalysisResponse>("/api/deal/analyze", {
    method: "POST",
    body: JSON.stringify({ documents, address, askingPrice, estimatedRepairs, notes }),
  });
}

export async function clearSession(sessionId: string): Promise<void> {
  await fetch(`/api/compliance/session/${sessionId}`, { method: "DELETE" }).catch(() => {});
}

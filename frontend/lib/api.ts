import type { AnalyzeResponse, AskResponse } from "./types";

const BASE = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
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

export async function clearSession(sessionId: string): Promise<void> {
  await fetch(`${BASE}/api/compliance/session/${sessionId}`, { method: "DELETE" });
}

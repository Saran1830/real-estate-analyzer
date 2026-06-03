import { analyzeDocument, askQuestion, clearSession } from "../api";

const mockAnalyzeResponse = {
  sessionId: "session-abc",
  riskLevel: "HIGH",
  summary: "High-risk contract.",
  findings: [],
  agentTrace: [],
};

const mockAskResponse = {
  answer: "Payment is due at closing.",
  confidence: "HIGH",
  sources: [],
  rerankScores: [],
  agentTrace: [],
};

beforeEach(() => {
  jest.resetAllMocks();
  global.fetch = jest.fn();
});

describe("analyzeDocument", () => {
  it("POSTs to /api/compliance/analyze and returns parsed response", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => mockAnalyzeResponse,
    });

    const result = await analyzeDocument("Contract text", "nda");

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/compliance/analyze"),
      expect.objectContaining({ method: "POST" })
    );
    expect(result.sessionId).toBe("session-abc");
    expect(result.riskLevel).toBe("HIGH");
  });

  it("sends X-Tenant-ID header", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => mockAnalyzeResponse,
    });

    await analyzeDocument("text", "nda", "my-tenant");

    const [, init] = (global.fetch as jest.Mock).mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Record<string, string>;
    expect(headers["X-Tenant-ID"]).toBe("my-tenant");
  });

  it("sends documentText and documentType in body", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => mockAnalyzeResponse,
    });

    await analyzeDocument("My contract", "wholesale_purchase_agreement");

    const [, init] = (global.fetch as jest.Mock).mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body.documentText).toBe("My contract");
    expect(body.documentType).toBe("wholesale_purchase_agreement");
  });

  it("throws when the server returns a non-OK status", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
      text: async () => "ChromaDB down",
    });

    await expect(analyzeDocument("text", "nda")).rejects.toThrow("500");
  });
});

describe("askQuestion", () => {
  it("POSTs to /api/compliance/ask and returns parsed response", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => mockAskResponse,
    });

    const result = await askQuestion("session-abc", "What are payment terms?");

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/compliance/ask"),
      expect.objectContaining({ method: "POST" })
    );
    expect(result.answer).toBe("Payment is due at closing.");
    expect(result.confidence).toBe("HIGH");
  });

  it("sends sessionId and question in body", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: true,
      json: async () => mockAskResponse,
    });

    await askQuestion("s-123", "What is the default penalty?");

    const [, init] = (global.fetch as jest.Mock).mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body.sessionId).toBe("s-123");
    expect(body.question).toBe("What is the default penalty?");
  });

  it("throws when the server returns 400", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({
      ok: false,
      status: 400,
      statusText: "Bad Request",
      text: async () => "Missing sessionId",
    });

    await expect(askQuestion("", "q")).rejects.toThrow("400");
  });
});

describe("clearSession", () => {
  it("sends DELETE to /api/compliance/session/{id}", async () => {
    (global.fetch as jest.Mock).mockResolvedValueOnce({ ok: true });

    await clearSession("session-abc");

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining("/api/compliance/session/session-abc"),
      expect.objectContaining({ method: "DELETE" })
    );
  });

  it("does not throw on network failure (fire-and-forget)", async () => {
    (global.fetch as jest.Mock).mockRejectedValueOnce(new Error("network error"));
    // clearSession swallows errors — should not throw
    await expect(clearSession("s")).resolves.toBeUndefined();
  });
});

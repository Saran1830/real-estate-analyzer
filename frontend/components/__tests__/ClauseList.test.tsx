import { render, screen } from "@testing-library/react";
import { ClauseList } from "../ClauseList";
import type { Finding } from "@/lib/types";

const findings: Finding[] = [
  { clause: "Assignment Clause", risk: "LOW",    explanation: "Standard.",        confidence: "HIGH"   },
  { clause: "Default Clause",    risk: "HIGH",   explanation: "Liquidated dmg.",  confidence: "HIGH"   },
  { clause: "Payment Terms",     risk: "MEDIUM", explanation: "Partial payment.", confidence: "MEDIUM" },
];

describe("ClauseList", () => {
  it("renders all findings", () => {
    render(<ClauseList findings={findings} />);
    expect(screen.getByText("Assignment Clause")).toBeInTheDocument();
    expect(screen.getByText("Default Clause")).toBeInTheDocument();
    expect(screen.getByText("Payment Terms")).toBeInTheDocument();
  });

  it("shows HIGH-risk findings before MEDIUM before LOW", () => {
    render(<ClauseList findings={findings} />);
    const clauses = screen
      .getAllByText(/Clause|Terms/)
      .map((el) => el.textContent ?? "");

    const highIdx   = clauses.findIndex((t) => t.includes("Default"));
    const mediumIdx = clauses.findIndex((t) => t.includes("Payment"));
    const lowIdx    = clauses.findIndex((t) => t.includes("Assignment"));

    expect(highIdx).toBeLessThan(mediumIdx);
    expect(mediumIdx).toBeLessThan(lowIdx);
  });

  it("renders empty-state message when findings list is empty", () => {
    render(<ClauseList findings={[]} />);
    expect(screen.getByText(/no findings/i)).toBeInTheDocument();
  });

  it("renders a single finding without crashing", () => {
    render(<ClauseList findings={[findings[0]]} />);
    expect(screen.getByText("Assignment Clause")).toBeInTheDocument();
  });
});

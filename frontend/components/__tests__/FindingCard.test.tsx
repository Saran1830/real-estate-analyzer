import { render, screen } from "@testing-library/react";
import { FindingCard } from "../FindingCard";
import type { Finding } from "@/lib/types";

const highFinding: Finding = {
  clause: "Default Clause",
  risk: "HIGH",
  explanation: "Buyer forfeits earnest money on default.",
  confidence: "HIGH",
};

const lowFinding: Finding = {
  clause: "Inspection Period",
  risk: "LOW",
  explanation: "Standard 10-day inspection window.",
  confidence: "MEDIUM",
};

describe("FindingCard", () => {
  it("renders the clause name", () => {
    render(<FindingCard finding={highFinding} />);
    expect(screen.getByText("Default Clause")).toBeInTheDocument();
  });

  it("renders the explanation text", () => {
    render(<FindingCard finding={highFinding} />);
    expect(
      screen.getByText("Buyer forfeits earnest money on default.")
    ).toBeInTheDocument();
  });

  it("renders both risk badge and confidence badge", () => {
    render(<FindingCard finding={highFinding} />);
    const badges = screen.getAllByText("HIGH");
    // risk level badge + confidence badge both show "HIGH"
    expect(badges.length).toBeGreaterThanOrEqual(2);
  });

  it("renders MEDIUM confidence badge separately from LOW risk", () => {
    render(<FindingCard finding={lowFinding} />);
    expect(screen.getByText("LOW")).toBeInTheDocument();
    expect(screen.getByText("MEDIUM")).toBeInTheDocument();
  });

  it("renders the clause label for a LOW risk finding", () => {
    render(<FindingCard finding={lowFinding} />);
    expect(screen.getByText("Inspection Period")).toBeInTheDocument();
  });
});

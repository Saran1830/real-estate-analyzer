import { render, screen } from "@testing-library/react";
import { RiskBadge } from "../RiskBadge";
import type { RiskLevel } from "@/lib/types";

describe("RiskBadge", () => {
  const levels: RiskLevel[] = ["HIGH", "MEDIUM", "LOW", "BLOCKED", "UNKNOWN"];

  test.each(levels)("renders %s label", (level) => {
    render(<RiskBadge level={level} />);
    expect(screen.getByText(level)).toBeInTheDocument();
  });

  it("applies red styling for HIGH risk", () => {
    render(<RiskBadge level="HIGH" />);
    const badge = screen.getByText("HIGH");
    expect(badge.className).toMatch(/red/);
  });

  it("applies amber styling for MEDIUM risk", () => {
    render(<RiskBadge level="MEDIUM" />);
    const badge = screen.getByText("MEDIUM");
    expect(badge.className).toMatch(/amber/);
  });

  it("applies green styling for LOW risk", () => {
    render(<RiskBadge level="LOW" />);
    const badge = screen.getByText("LOW");
    expect(badge.className).toMatch(/green/);
  });

  it("renders with lg size classes when size=lg", () => {
    render(<RiskBadge level="HIGH" size="lg" />);
    const badge = screen.getByText("HIGH");
    expect(badge.className).toMatch(/text-sm/);
  });

  it("renders with sm size classes by default", () => {
    render(<RiskBadge level="HIGH" />);
    const badge = screen.getByText("HIGH");
    expect(badge.className).toMatch(/text-xs/);
  });

  it("renders as an inline span", () => {
    render(<RiskBadge level="LOW" />);
    expect(screen.getByText("LOW").tagName).toBe("SPAN");
  });
});

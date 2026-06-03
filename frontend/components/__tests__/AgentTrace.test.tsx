import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AgentTrace } from "../AgentTrace";
import type { NodeExecution } from "@/lib/types";

const trace: NodeExecution[] = [
  { node: "guardrail", status: "VALID",  latencyMs: 340,  detail: "VALID" },
  { node: "ingest",    status: "OK",     latencyMs: 1200, detail: "Document chunked and embedded" },
  { node: "analyze",   status: "OK",     latencyMs: 2800, detail: "Risk: HIGH, Findings: 4" },
];

describe("AgentTrace", () => {
  it("renders the Agent Trace heading", () => {
    render(<AgentTrace trace={trace} />);
    expect(screen.getByText(/Agent Trace/i)).toBeInTheDocument();
  });

  it("shows node count and total latency in collapsed state", () => {
    render(<AgentTrace trace={trace} />);
    expect(screen.getByText(/3 nodes/)).toBeInTheDocument();
    // total = 340 + 1200 + 2800 = 4340ms
    expect(screen.getByText(/4340ms total/)).toBeInTheDocument();
  });

  it("hides node details when collapsed by default", () => {
    render(<AgentTrace trace={trace} />);
    expect(screen.queryByText("guardrail")).not.toBeInTheDocument();
  });

  it("expands to show node names when header is clicked", async () => {
    render(<AgentTrace trace={trace} />);
    await userEvent.click(screen.getByRole("button"));
    expect(screen.getByText("guardrail")).toBeInTheDocument();
    expect(screen.getByText("ingest")).toBeInTheDocument();
    expect(screen.getByText("analyze")).toBeInTheDocument();
  });

  it("shows node detail text when expanded", async () => {
    render(<AgentTrace trace={trace} />);
    await userEvent.click(screen.getByRole("button"));
    expect(screen.getByText("Document chunked and embedded")).toBeInTheDocument();
  });

  it("collapses again when header is clicked a second time", async () => {
    render(<AgentTrace trace={trace} />);
    const btn = screen.getByRole("button");
    await userEvent.click(btn);
    await userEvent.click(btn);
    expect(screen.queryByText("guardrail")).not.toBeInTheDocument();
  });

  it("renders an empty trace without crashing", () => {
    render(<AgentTrace trace={[]} />);
    expect(screen.getByText(/0 nodes/)).toBeInTheDocument();
  });
});

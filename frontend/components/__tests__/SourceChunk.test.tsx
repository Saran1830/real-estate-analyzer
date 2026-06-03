import { render, screen } from "@testing-library/react";
import { SourceChunk } from "../SourceChunk";
import type { SourceChunk as SourceChunkType } from "@/lib/types";

const chunk: SourceChunkType = {
  text: "Payment is due at closing as per Section 5.",
  cosineScore: 0.84,
  rerankScore: 0.95,
};

describe("SourceChunk", () => {
  it("renders the chunk text", () => {
    render(<SourceChunk chunk={chunk} rank={1} />);
    expect(
      screen.getByText(/Payment is due at closing/)
    ).toBeInTheDocument();
  });

  it("renders the rank label", () => {
    render(<SourceChunk chunk={chunk} rank={3} />);
    expect(screen.getByText("Source #3")).toBeInTheDocument();
  });

  it("displays cosine score", () => {
    render(<SourceChunk chunk={chunk} rank={1} />);
    expect(screen.getByText("0.84")).toBeInTheDocument();
  });

  it("displays rerank score", () => {
    render(<SourceChunk chunk={chunk} rank={1} />);
    expect(screen.getByText("0.95")).toBeInTheDocument();
  });

  it("renders Cosine and Re-rank labels", () => {
    render(<SourceChunk chunk={chunk} rank={1} />);
    expect(screen.getByText("Cosine")).toBeInTheDocument();
    expect(screen.getByText("Re-rank")).toBeInTheDocument();
  });
});

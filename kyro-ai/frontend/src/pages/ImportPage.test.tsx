import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ImportPage from "./ImportPage";

const mocks = vi.hoisted(() => ({
  get: vi.fn(), upload: vi.fn(), post: vi.fn(), del: vi.fn(),
  toast: vi.fn(), refreshConversations: vi.fn(), refreshProjects: vi.fn(),
}));
vi.mock("../lib/api", () => ({ api: mocks }));
vi.mock("../lib/store", () => ({
  timeAgo: () => "just now",
  useApp: () => ({ projects: [], ...mocks }),
}));

beforeEach(() => {
  mocks.get.mockResolvedValue([]);
  mocks.upload.mockResolvedValue({ id: "job-1", status: "queued" });
  mocks.post.mockResolvedValue({});
  mocks.del.mockResolvedValue({});
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("archive import controls", () => {
  it("queues conversation files as a background archive job", async () => {
    const { container } = render(<ImportPage />);
    const file = new File(['{"role":"user","content":"hello"}'], "messages.jsonl");
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
    await waitFor(() => expect(mocks.upload).toHaveBeenCalled());
    expect(mocks.upload.mock.calls[0][0]).toBe("/archive/jobs");
    const form = mocks.upload.mock.calls[0][1] as FormData;
    expect(form.get("file")).toBe(file);
    expect(await screen.findByText("Upload received. Follow the background job below.")).toBeTruthy();
  });

  it("keeps the standard importer for memories, documents and project exports", async () => {
    const { container } = render(<ImportPage />);
    fireEvent.change(screen.getByLabelText("Type"), { target: { value: "auto" } });
    fireEvent.change(container.querySelector('input[type="file"]')!, {
      target: { files: [new File(["[]"], "memories.json")] },
    });
    await waitFor(() => expect(mocks.upload).toHaveBeenCalled());
    expect(mocks.upload.mock.calls[0][0]).toBe("/import");
  });

  it("offers persisted jobs for resuming and indexing older conversations", async () => {
    mocks.get.mockImplementation((path: string) => Promise.resolve(path === "/archive/jobs" ? [{
      id: "paused-job", filename: "large.jsonl", status: "interrupted", processed: 500,
      inserted: 500, updated: 0, duplicates: 0, skipped: 0,
    }] : []));
    render(<ImportPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Resume" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith("/archive/jobs/paused-job/resume"));
    fireEvent.click(screen.getByRole("button", { name: "Index existing conversations" }));
    await waitFor(() => expect(mocks.post).toHaveBeenCalledWith("/archive/reindex"));
    expect(screen.getByText(/500 processed/)).toBeTruthy();
  });
});

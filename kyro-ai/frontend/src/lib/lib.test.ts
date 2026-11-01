import { describe, expect, it } from "vitest";
import { SSEParser } from "./sse";
import { extractCodeBlocks, guessFilename, isPreviewable, previewDoc } from "./artifacts";

describe("SSEParser", () => {
  it("parses events split across chunks", () => {
    const p = new SSEParser();
    expect(p.feed('event: delta\ndata: {"delta":"he')).toEqual([]);
    const ev = p.feed('llo"}\n\nevent: done\ndata: {}\n\n: keep-alive\n\n');
    expect(ev).toEqual([
      { event: "delta", data: { delta: "hello" } },
      { event: "done", data: {} },
    ]);
  });
  it("handles CRLF and non-JSON data", () => {
    expect(new SSEParser().feed("data: plain\r\n\r\n")).toEqual([{ event: "message", data: "plain" }]);
  });
});

describe("artifacts", () => {
  it("extracts code blocks with filenames", () => {
    const md = "Here:\n```java\npublic final class SpeedCheck {}\n```\n```python\n# tools/run.py\nprint(1)\n```\n```html\n<h1>x</h1>\n```";
    const b = extractCodeBlocks(md);
    expect(b.map((x) => x.filename)).toEqual(["SpeedCheck.java", "tools/run.py", "snippet-3.html"]);
    expect(isPreviewable(b[2].lang, b[2].code)).toBe(true);
    expect(isPreviewable("java", "")).toBe(false);
  });
  it("guesses filenames from comments", () => {
    expect(guessFilename("// File: src/Main.ts\nlet a", "ts")).toBe("src/Main.ts");
    expect(guessFilename("<!-- index.html -->\n<p>", "html")).toBe("index.html");
  });
  it("wraps fragments into documents", () => {
    expect(previewDoc("html", "<p>x</p>")).toContain("<body><p>x</p></body>");
    expect(previewDoc("html", "<html><body>y</body></html>")).toBe("<html><body>y</body></html>");
  });
});

export type CodeBlock = { lang: string; code: string; filename?: string; index: number };

const EXT: Record<string, string> = {
  java: "java", python: "py", py: "py", javascript: "js", js: "js", typescript: "ts", ts: "ts", tsx: "tsx", jsx: "jsx",
  html: "html", css: "css", json: "json", yaml: "yml", yml: "yml", xml: "xml", rust: "rs", go: "go", cpp: "cpp", "c++": "cpp",
  c: "c", csharp: "cs", cs: "cs", kotlin: "kt", bash: "sh", sh: "sh", shell: "sh", powershell: "ps1", ps1: "ps1", sql: "sql",
  markdown: "md", md: "md", toml: "toml", groovy: "gradle", svg: "svg", bat: "bat", properties: "properties",
};

/** Extract fenced code blocks; detect a filename from the first-line comment (// File: x, # path/to/x.py, <!-- a.html -->). */
export function extractCodeBlocks(markdown: string): CodeBlock[] {
  const out: CodeBlock[] = [];
  const re = /```([\w+#.-]*)[^\n]*\n([\s\S]*?)```/g;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = re.exec(markdown))) {
    const lang = (m[1] || "text").toLowerCase();
    const code = m[2];
    out.push({ lang, code, filename: guessFilename(code, lang, i), index: i });
    i++;
  }
  return out;
}

export function guessFilename(code: string, lang: string, i = 0): string {
  const first = code.split("\n", 1)[0].trim();
  const m = first.match(/^(?:\/\/|#|--|<!--|\/\*)\s*(?:file(?:name)?:\s*)?([\w./\\-]+\.[A-Za-z0-9]{1,10})\s*(?:-->|\*\/)?$/i);
  if (m) return m[1].replace(/\\/g, "/");
  if (lang === "java") {
    const c = code.match(/public\s+(?:final\s+|abstract\s+)?(?:class|interface|enum|record)\s+(\w+)/);
    if (c) return `${c[1]}.java`;
  }
  return `snippet-${i + 1}.${EXT[lang] || "txt"}`;
}

export function isPreviewable(lang: string, code: string): boolean {
  if (lang === "html" || lang === "svg") return true;
  return lang === "xml" && code.trimStart().startsWith("<svg");
}

/** Wrap content for a sandboxed iframe (scripts run, but without same-origin access to KYRO). */
export function previewDoc(lang: string, code: string): string {
  if (lang === "svg" || (lang === "xml" && code.trimStart().startsWith("<svg")))
    return `<!doctype html><html><body style="margin:0;display:grid;place-items:center;min-height:100vh;background:#fff">${code}</body></html>`;
  if (/<html[\s>]/i.test(code)) return code;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>${code}</body></html>`;
}

// Bundle Monaco locally so the editor works fully offline (no CDN).
import * as monaco from "monaco-editor";
import { loader } from "@monaco-editor/react";
import EditorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import JsonWorker from "monaco-editor/esm/vs/language/json/json.worker?worker";
import CssWorker from "monaco-editor/esm/vs/language/css/css.worker?worker";
import HtmlWorker from "monaco-editor/esm/vs/language/html/html.worker?worker";
import TsWorker from "monaco-editor/esm/vs/language/typescript/ts.worker?worker";

(self as any).MonacoEnvironment = {
  getWorker(_: unknown, label: string) {
    if (label === "json") return new JsonWorker();
    if (["css", "scss", "less"].includes(label)) return new CssWorker();
    if (["html", "handlebars", "razor"].includes(label)) return new HtmlWorker();
    if (["typescript", "javascript"].includes(label)) return new TsWorker();
    return new EditorWorker();
  },
};
loader.config({ monaco });

const LANG: Record<string, string> = {
  java: "java", py: "python", js: "javascript", mjs: "javascript", jsx: "javascript", ts: "typescript", tsx: "typescript",
  json: "json", html: "html", htm: "html", css: "css", scss: "scss", md: "markdown", yml: "yaml", yaml: "yaml", xml: "xml",
  rs: "rust", go: "go", cpp: "cpp", cc: "cpp", h: "cpp", hpp: "cpp", c: "c", cs: "csharp", kt: "kotlin", kts: "kotlin",
  gradle: "groovy", groovy: "groovy", sh: "shell", bat: "bat", ps1: "powershell", sql: "sql", toml: "ini", properties: "ini",
  ini: "ini", lua: "lua", php: "php", rb: "ruby", swift: "swift", dockerfile: "dockerfile",
};
export function languageFor(path: string): string {
  const name = path.split("/").pop()!.toLowerCase();
  if (name === "dockerfile") return "dockerfile";
  return LANG[name.split(".").pop() || ""] || "plaintext";
}
export { monaco };

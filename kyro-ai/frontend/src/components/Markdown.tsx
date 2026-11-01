import { memo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import rehypeHighlight from "rehype-highlight";
import { Check, Copy, Download, Eye } from "lucide-react";
import { guessFilename, isPreviewable } from "../lib/artifacts";
import { saveBlob } from "../lib/api";

export type PreviewFn = (a: { lang: string; code: string; title: string }) => void;

function textOf(node: any): string {
  if (typeof node === "string") return node;
  if (Array.isArray(node)) return node.map(textOf).join("");
  if (node?.props?.children) return textOf(node.props.children);
  return "";
}

function CodeBlock({ lang, children, onPreview, index }: { lang: string; children: any; onPreview?: PreviewFn; index: number }) {
  const [copied, setCopied] = useState(false);
  const code = textOf(children).replace(/\n$/, "");
  const filename = guessFilename(code, lang, index);
  return (
    <div className="not-prose my-3 overflow-hidden rounded-lg border border-border">
      <div className="flex items-center gap-1 border-b border-border bg-panel2 px-3 py-1 text-xs text-muted">
        <span className="font-mono">{lang || "text"}</span>
        <span className="ml-2 truncate opacity-70">{filename}</span>
        <span className="flex-1" />
        {onPreview && isPreviewable(lang, code) && (
          <button className="btn-ghost px-1.5 py-0.5 text-xs" onClick={() => onPreview({ lang, code, title: filename })}><Eye size={13} /> Preview</button>
        )}
        <button className="btn-ghost px-1.5 py-0.5 text-xs" onClick={() => saveBlob(code, filename.split("/").pop()!)} title="Download"><Download size={13} /></button>
        <button className="btn-ghost px-1.5 py-0.5 text-xs" title="Copy"
          onClick={() => { navigator.clipboard.writeText(code); setCopied(true); setTimeout(() => setCopied(false), 1500); }}>
          {copied ? <Check size={13} /> : <Copy size={13} />}
        </button>
      </div>
      <pre className="overflow-x-auto bg-panel p-3 text-[13px] leading-relaxed"><code className="font-mono">{children}</code></pre>
    </div>
  );
}

function MarkdownImpl({ text, onPreview }: { text: string; onPreview?: PreviewFn }) {
  let blockIndex = 0;
  return (
    <div className="prose-kyro">
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex, [rehypeHighlight, { detect: false, ignoreMissing: true }]]}
        components={{
          pre: ({ children }) => <>{children}</>,
          code: ({ className, children }) => {
            const m = /language-([\w+#.-]+)/.exec(className || "");
            const isBlock = !!m || String(textOf(children)).includes("\n");
            if (!isBlock) return <code>{children}</code>;
            return <CodeBlock lang={m?.[1] || ""} onPreview={onPreview} index={blockIndex++}>{children}</CodeBlock>;
          },
          a: ({ href, children }) => <a href={href} target="_blank" rel="noreferrer noopener">{children}</a>,
          table: ({ children }) => <div className="overflow-x-auto"><table>{children}</table></div>,
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

export const Markdown = memo(MarkdownImpl);

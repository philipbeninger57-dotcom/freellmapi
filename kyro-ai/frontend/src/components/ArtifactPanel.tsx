import { useState } from "react";
import { Code, Download, Eye, X } from "lucide-react";
import { previewDoc } from "../lib/artifacts";
import { saveBlob } from "../lib/api";

export type Artifact = { lang: string; code: string; title: string };

/** Live preview in a sandboxed iframe: scripts run, but without access to KYRO's origin, cookies or API. */
export default function ArtifactPanel({ artifact, onClose }: { artifact: Artifact; onClose: () => void }) {
  const [tab, setTab] = useState<"preview" | "code">("preview");
  return (
    <aside className="flex h-full min-w-0 flex-col border-l border-border bg-panel">
      <div className="flex items-center gap-1 border-b border-border px-3 py-2">
        <span className="truncate text-sm font-medium">{artifact.title}</span>
        <span className="flex-1" />
        <button className={`btn-ghost px-2 py-1 ${tab === "preview" ? "bg-panel2" : ""}`} onClick={() => setTab("preview")}><Eye size={14} /> Preview</button>
        <button className={`btn-ghost px-2 py-1 ${tab === "code" ? "bg-panel2" : ""}`} onClick={() => setTab("code")}><Code size={14} /> Code</button>
        <button className="btn-ghost px-2 py-1" onClick={() => saveBlob(previewDoc(artifact.lang, artifact.code), artifact.title.replace(/\.[^.]+$/, "") + ".html", "text/html")}><Download size={14} /></button>
        <button className="btn-ghost px-2 py-1" onClick={onClose}><X size={14} /></button>
      </div>
      {tab === "preview" ? (
        <iframe title="artifact preview" className="min-h-0 flex-1 bg-white" sandbox="allow-scripts allow-forms allow-modals" srcDoc={previewDoc(artifact.lang, artifact.code)} />
      ) : (
        <pre className="min-h-0 flex-1 overflow-auto p-3 font-mono text-xs">{artifact.code}</pre>
      )}
    </aside>
  );
}

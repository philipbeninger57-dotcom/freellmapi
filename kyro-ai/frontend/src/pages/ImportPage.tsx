import { useEffect, useRef, useState } from "react";
import clsx from "clsx";
import { CheckCircle2, Loader2, Upload, XCircle } from "lucide-react";
import { api } from "../lib/api";
import { timeAgo, useApp } from "../lib/store";

const FILE_KINDS = [
  { id: "auto", label: "Auto-detect (ChatGPT / Claude export ZIP or conversations.json, projects.json, memories)" },
  { id: "chatgpt_memory", label: "ChatGPT memory list (.txt/.json)" },
  { id: "claude_memory", label: "Claude memory (.txt/.json)" },
  { id: "custom_instructions", label: "Custom instructions (.txt/.md)" },
  { id: "note", label: "Notes / documents (.md, .txt, .pdf, .docx)" },
  { id: "project_zip", label: "Programming project (.zip of a repository)" },
];
const TEXT_KINDS = [
  { id: "chatgpt_memory", label: "ChatGPT memory (Settings → Personalization → Manage memories — copy all)" },
  { id: "claude_memory", label: "Claude memory / project knowledge" },
  { id: "custom_instructions", label: "Custom instructions" },
  { id: "note", label: "Personal note" },
];

export default function ImportPage() {
  const { projects, toast, refreshConversations, refreshProjects } = useApp();
  const [kind, setKind] = useState("auto");
  const [projectId, setProjectId] = useState("");
  const [useLlm, setUseLlm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [history, setHistory] = useState<any[]>([]);
  const [textKind, setTextKind] = useState("chatgpt_memory");
  const [text, setText] = useState("");
  const [drag, setDrag] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  const loadHistory = () => api.get("/imports").then(setHistory).catch(() => {});
  useEffect(() => { loadHistory(); }, []);

  const importFiles = async (files: FileList | File[]) => {
    for (const f of Array.from(files)) {
      const fd = new FormData();
      fd.append("file", f); fd.append("kind", kind); fd.append("project_id", projectId); fd.append("use_llm", String(useLlm));
      setBusy(true); setResult(null);
      try {
        const r = await api.upload("/import", fd);
        setResult({ file: f.name, ...r });
        toast(`Imported ${f.name}`, "success");
      } catch (e: any) { toast(`${f.name}: ${e.message}`); setResult({ file: f.name, error: e.message }); }
      finally { setBusy(false); }
    }
    loadHistory(); refreshConversations(); refreshProjects();
  };
  const importText = async () => {
    setBusy(true);
    try { const r = await api.post("/import/text", { text, kind: textKind, project_id: projectId || null }); setResult({ file: "pasted text", ...r }); setText(""); loadHistory(); }
    catch (e: any) { toast(e.message); } finally { setBusy(false); }
  };

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl space-y-5 p-4 md:p-6">
        <div>
          <h1 className="font-serif text-2xl">Import your knowledge</h1>
          <p className="mt-1 text-sm text-muted">KYRO only reads files you export and upload here. It never connects to your ChatGPT or Claude accounts. Everything stays in your local database.</p>
        </div>
        <div className="card space-y-2 p-4 text-sm">
          <div className="font-medium">How to export</div>
          <ul className="list-disc space-y-1 pl-5 text-muted">
            <li><b>ChatGPT:</b> Settings → Data controls → Export data. Upload the ZIP you receive by e-mail.</li>
            <li><b>ChatGPT memory:</b> Settings → Personalization → Manage memories. Copy the list and paste it below.</li>
            <li><b>Claude:</b> Settings → Privacy → Export data. Upload the ZIP (conversations, projects with instructions & docs).</li>
            <li><b>Claude memory / project instructions:</b> copy them and paste below, or upload as .txt.</li>
          </ul>
        </div>
        <div className="card space-y-3 p-4">
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1"><span className="label">Type</span>
              <select className="input" value={kind} onChange={(e) => setKind(e.target.value)}>{FILE_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</select>
            </label>
            <label className="space-y-1"><span className="label">Attach to project (optional)</span>
              <select className="input" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
                <option value="">General memory{kind === "project_zip" ? " (new project from ZIP name)" : ""}</option>
                {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </label>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={useLlm} onChange={(e) => setUseLlm(e.target.checked)} />
            Also use the local model to extract facts (slower; every extracted fact is checked against your original words and rejected if not grounded)
          </label>
          <div onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)}
            onDrop={(e) => { e.preventDefault(); setDrag(false); importFiles(e.dataTransfer.files); }}
            onClick={() => fileRef.current?.click()}
            className={clsx("grid cursor-pointer place-items-center rounded-xl border-2 border-dashed p-10 text-center transition-colors", drag ? "border-accent bg-accent/5" : "border-border hover:border-accent/60")}>
            {busy ? <Loader2 className="animate-spin text-accent" /> : <Upload className="text-muted" />}
            <div className="mt-2 text-sm">{busy ? "Importing… large exports can take a few minutes" : "Drop files here or click to choose"}</div>
            <div className="text-xs text-muted">.zip · .json · .txt · .md · .pdf · .docx</div>
          </div>
          <input ref={fileRef} type="file" multiple hidden onChange={(e) => { if (e.target.files) importFiles(e.target.files); e.target.value = ""; }} />
        </div>
        <div className="card space-y-3 p-4">
          <div className="font-medium">Paste text</div>
          <select className="input" value={textKind} onChange={(e) => setTextKind(e.target.value)}>{TEXT_KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}</select>
          <textarea className="input min-h-[140px] font-mono text-xs" placeholder={"- Prefers explanations in German\n- Runs Windows 11 with 32 GB RAM\n- Develops KYRO AntiCheat with Maven"} value={text} onChange={(e) => setText(e.target.value)} />
          <button className="btn-primary" disabled={!text.trim() || busy} onClick={importText}>Import text</button>
        </div>
        {result && (
          <div className={clsx("card p-4 text-sm", result.error && "border-red-500/50")}>
            <div className="mb-2 flex items-center gap-2 font-medium">{result.error ? <XCircle className="text-red-500" size={16} /> : <CheckCircle2 className="text-emerald-500" size={16} />}{result.file}</div>
            {result.error ? <div className="text-red-600">{result.error}</div> : <Stats s={result} />}
          </div>
        )}
        <div>
          <div className="label mb-2">Import history</div>
          <div className="space-y-1.5">
            {history.map((h) => (
              <div key={h.id} className="card flex flex-wrap items-center gap-2 px-3 py-2 text-xs">
                {h.status === "done" ? <CheckCircle2 size={14} className="text-emerald-500" /> : h.status === "failed" ? <XCircle size={14} className="text-red-500" /> : <Loader2 size={14} className="animate-spin" />}
                <span className="font-medium">{h.filename}</span><span className="text-muted">{h.kind} · {timeAgo(h.created_at)}</span>
                <span className="flex-1" />
                {h.error ? <span className="text-red-600">{h.error}</span> : <Stats s={h.stats} compact />}
              </div>
            ))}
            {!history.length && <div className="text-sm text-muted">Nothing imported yet.</div>}
          </div>
        </div>
      </div>
    </div>
  );
}

function Stats({ s, compact }: { s: any; compact?: boolean }) {
  const items = [
    ["conversations", s.conversations], ["messages", s.messages], ["new memories", s.memories_created], ["duplicates merged", s.duplicates],
    ["conflicts flagged", s.conflicts], ["projects", s.projects], ["notes", s.notes], ["rejected (ungrounded)", s.rejected_ungrounded],
    ["files", typeof s.files === "number" ? s.files : undefined],
  ].filter(([, v]) => v);
  return (
    <div className={clsx("flex flex-wrap gap-1.5", compact && "text-[11px]")}>
      {items.map(([k, v]) => <span key={k as string} className="chip">{v} {k}</span>)}
      {!compact && s.skipped?.length > 0 && <div className="w-full text-xs text-amber-600">Skipped: {s.skipped.join("; ")}</div>}
      {!compact && s.workspace && <div className="w-full text-xs text-muted">Extracted to {s.workspace}</div>}
    </div>
  );
}

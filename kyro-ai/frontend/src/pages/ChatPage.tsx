import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import clsx from "clsx";
import {
  ArrowUp, Brain, ChevronDown, ChevronRight, Copy, Download, FileText, Folder, Globe, Image as ImageIcon, Loader2,
  Menu, MessageSquarePlus, Paperclip, Pin, RefreshCw, Search, Square, Trash2, X,
} from "lucide-react";
import { api, download, stream } from "../lib/api";
import { timeAgo, useApp } from "../lib/store";
import { Markdown } from "../components/Markdown";
import ModelSelect from "../components/ModelSelect";
import ArtifactPanel, { Artifact } from "../components/ArtifactPanel";

type Msg = {
  id: string; role: "user" | "assistant"; content: string; model?: string; attachments: string[]; meta: any;
  streaming?: boolean; thinking?: string; status?: string;
};
type Att = { id: string; filename: string; kind: string; size: number; analysis?: any; error?: string };

const MODES = [
  { id: "chat", label: "Chat" }, { id: "reasoning", label: "Reasoning" }, { id: "coding", label: "Coding" },
  { id: "project", label: "Project" }, { id: "research", label: "Research" }, { id: "vision", label: "Vision" },
  { id: "document", label: "Document" },
];

function Sidebar({ activeId, onNavigate }: { activeId?: string; onNavigate?: () => void }) {
  const { conversations, projects, refreshConversations, toast } = useApp();
  const navigate = useNavigate();
  const nav = (to: string) => { navigate(to); onNavigate?.(); };
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<any>(null);
  const [open, setOpen] = useState<Record<string, boolean>>({ recent: true });

  useEffect(() => {
    if (q.trim().length < 2) { setHits(null); return; }
    const t = setTimeout(() => api.get("/search", { q }).then(setHits).catch((e) => toast(e.message)), 250);
    return () => clearTimeout(t);
  }, [q, toast]);

  const remove = async (id: string) => {
    if (!confirm("Delete this conversation permanently?")) return;
    await api.del(`/conversations/${id}`);
    await refreshConversations();
    if (id === activeId) nav("/");
  };
  const togglePin = async (c: any) => { await api.patch(`/conversations/${c.id}`, { pinned: c.pinned ? 0 : 1 }); refreshConversations(); };

  const groups: { key: string; label: string; items: any[]; icon?: any }[] = [];
  const pinned = conversations.filter((c) => c.pinned);
  if (pinned.length) groups.push({ key: "pinned", label: "Pinned", items: pinned, icon: Pin });
  for (const p of projects) {
    const items = conversations.filter((c) => c.project_id === p.id && !c.pinned);
    groups.push({ key: p.id, label: p.name, items, icon: Folder });
  }
  groups.push({ key: "recent", label: "Recent", items: conversations.filter((c) => !c.project_id && !c.pinned && c.source === "kyro") });
  for (const src of ["chatgpt", "claude"]) {
    const items = conversations.filter((c) => c.source === src && !c.project_id && !c.pinned);
    if (items.length) groups.push({ key: src, label: src === "chatgpt" ? "Imported · ChatGPT" : "Imported · Claude", items });
  }

  return (
    <aside className="flex h-full w-72 shrink-0 flex-col border-r border-border bg-panel/60">
      <div className="space-y-2 p-3">
        <button className="btn-primary w-full justify-center" onClick={() => nav("/")}><MessageSquarePlus size={16} /> New chat</button>
        <div className="relative">
          <Search size={14} className="absolute left-2.5 top-2.5 text-muted" />
          <input className="input pl-8" placeholder="Search all conversations…" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-3 text-sm">
        {hits ? (
          <div className="space-y-1">
            <div className="label px-2 py-1">Results</div>
            {[...hits.conversations.map((c: any) => ({ id: c.id, title: c.title, snippet: "" })),
              ...hits.messages.map((m: any) => ({ id: m.conversation_id, title: m.title, snippet: m.snippet }))].map((h, i) => (
              <button key={i} onClick={() => { nav(`/c/${h.id}`); setQ(""); }} className="block w-full rounded-lg px-2 py-1.5 text-left hover:bg-panel2">
                <div className="truncate font-medium">{h.title}</div>
                {h.snippet && <div className="line-clamp-2 text-xs text-muted">{h.snippet}</div>}
              </button>
            ))}
            {!hits.conversations.length && !hits.messages.length && <div className="px-2 text-muted">No matches.</div>}
          </div>
        ) : groups.map((g) => {
          const isOpen = open[g.key] ?? (g.key === "pinned" || g.items.some((c) => c.id === activeId));
          return (
            <div key={g.key} className="mb-1">
              <button className="flex w-full items-center gap-1 rounded px-2 py-1 text-xs font-medium text-muted hover:text-fg" onClick={() => setOpen({ ...open, [g.key]: !isOpen })}>
                {isOpen ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                {g.icon && <g.icon size={13} />}
                <span className="truncate">{g.label}</span>
                <span className="ml-auto opacity-60">{g.items.length}</span>
              </button>
              {isOpen && g.items.slice(0, 200).map((c) => (
                <div key={c.id} className={clsx("group flex items-center rounded-lg pr-1 hover:bg-panel2", c.id === activeId && "bg-panel2")}>
                  <button onClick={() => nav(`/c/${c.id}`)} className="min-w-0 flex-1 truncate px-2 py-1.5 text-left" title={`${c.title} · ${timeAgo(c.updated_at)}`}>{c.title}</button>
                  <button onClick={() => togglePin(c)} className="hidden p-1 text-muted hover:text-fg group-hover:block" title={c.pinned ? "Unpin" : "Pin"}><Pin size={13} /></button>
                  <button onClick={() => remove(c.id)} className="hidden p-1 text-muted hover:text-red-500 group-hover:block" title="Delete"><Trash2 size={13} /></button>
                </div>
              ))}
              {isOpen && !g.items.length && <div className="px-7 py-1 text-xs text-muted">No conversations</div>}
            </div>
          );
        })}
      </div>
    </aside>
  );
}

function AttachmentChip({ a, onRemove }: { a: Att; onRemove?: () => void }) {
  const isImg = a.kind === "image";
  return (
    <div className={clsx("chip max-w-[240px]", a.error && "border-red-500/50 text-red-600")} title={a.error || a.analysis?.note || a.filename}>
      {isImg ? <img src={api.url(`/files/${a.id}/raw`)} className="h-6 w-6 rounded object-cover" alt="" /> : <FileText size={13} />}
      <span className="truncate">{a.filename}</span>
      {onRemove && <button onClick={onRemove} className="opacity-60 hover:opacity-100"><X size={12} /></button>}
    </div>
  );
}

function Message({ m, atts, onPreview, onRegenerate, isLast }: { m: Msg; atts: Record<string, Att>; onPreview: (a: Artifact) => void; onRegenerate: () => void; isLast: boolean }) {
  const [showThink, setShowThink] = useState(false);
  const [showMem, setShowMem] = useState(false);
  if (m.role === "user") {
    return (
      <div className="flex flex-col items-end gap-1.5">
        {m.attachments.length > 0 && (
          <div className="flex flex-wrap justify-end gap-1.5">
            {m.attachments.map((id) => atts[id]?.kind === "image"
              ? <img key={id} src={api.url(`/files/${id}/raw`)} className="max-h-48 rounded-lg border border-border" alt={atts[id]?.filename} />
              : atts[id] ? <AttachmentChip key={id} a={atts[id]} /> : null)}
          </div>
        )}
        <div className="max-w-[85%] whitespace-pre-wrap rounded-2xl bg-panel2 px-4 py-2.5">{m.content}</div>
      </div>
    );
  }
  const meta = m.meta || {};
  const thinking = m.thinking || meta.thinking;
  return (
    <div className="group">
      {m.status && m.streaming && <div className="mb-2 flex items-center gap-2 text-xs text-muted"><Loader2 size={13} className="animate-spin" />{m.status}</div>}
      {thinking && (
        <div className="mb-2">
          <button className="flex items-center gap-1 text-xs text-muted hover:text-fg" onClick={() => setShowThink(!showThink)}>
            {showThink ? <ChevronDown size={13} /> : <ChevronRight size={13} />} Reasoning
          </button>
          {showThink && <div className="mt-1 whitespace-pre-wrap border-l-2 border-border pl-3 text-xs text-muted">{thinking}</div>}
        </div>
      )}
      <div className={clsx(m.streaming && !m.content && "cursor-blink")}>
        <Markdown text={m.content} onPreview={onPreview} />
        {m.streaming && m.content && <span className="cursor-blink" />}
      </div>
      {meta.error && <div className="mt-2 rounded-lg border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-700 dark:text-red-300">{meta.error}</div>}
      {meta.citations?.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {meta.citations.map((c: any) => (
            <a key={c.n} href={c.url} target="_blank" rel="noreferrer noopener" className="chip hover:border-accent" title={c.url}>
              <span className="font-semibold text-accent">[{c.n}]</span><span className="max-w-[220px] truncate">{c.title || c.url}</span>
            </a>
          ))}
        </div>
      )}
      {!m.streaming && (
        <div className="mt-2 flex flex-wrap items-center gap-1 text-xs text-muted opacity-70 transition-opacity group-hover:opacity-100">
          {m.model && <span className="chip">{m.model}</span>}
          {meta.memories?.length > 0 && (
            <button className="chip hover:border-accent" onClick={() => setShowMem(!showMem)}><Brain size={12} /> {meta.memories.length} memories</button>
          )}
          {meta.documents?.length > 0 && <span className="chip"><FileText size={12} /> {meta.documents.length} files</span>}
          {meta.stats?.eval_count && meta.elapsed ? <span>{meta.stats.eval_count} tok · {meta.elapsed}s</span> : null}
          <button className="btn-ghost px-1.5 py-0.5" onClick={() => navigator.clipboard.writeText(m.content)} title="Copy"><Copy size={13} /></button>
          {isLast && <button className="btn-ghost px-1.5 py-0.5" onClick={onRegenerate} title="Regenerate"><RefreshCw size={13} /></button>}
        </div>
      )}
      {showMem && (
        <ul className="mt-2 space-y-1 rounded-lg border border-border bg-panel p-2 text-xs">
          {meta.memories.map((x: any) => <li key={x.id}><span className="text-muted">[{x.source} · {x.score}]</span> {x.content}</li>)}
        </ul>
      )}
    </div>
  );
}

export default function ChatPage() {
  const { id } = useParams();
  const nav = useNavigate();
  const { projects, refreshConversations, toast, models } = useApp();
  const [conv, setConv] = useState<any>(null);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [atts, setAtts] = useState<Record<string, Att>>({});
  const [text, setText] = useState("");
  const [pending, setPending] = useState<Att[]>([]);
  const [uploading, setUploading] = useState(false);
  const [mode, setMode] = useState("chat");
  const [model, setModel] = useState("auto");
  const [projectId, setProjectId] = useState<string>("");
  const [web, setWeb] = useState(false);
  const [busy, setBusy] = useState(false);
  const [drag, setDrag] = useState(false);
  const [artifact, setArtifact] = useState<Artifact | null>(null);
  const [mobileList, setMobileList] = useState(false);
  const abortRef = useRef<() => void>();
  const bottomRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const skipLoad = useRef<string | null>(null);

  const load = useCallback(async (cid: string) => {
    try {
      const c = await api.get(`/conversations/${cid}`);
      setConv(c);
      setMsgs(c.messages.map((m: any) => ({ ...m })));
      setAtts(c.attachments || {});
      setMode(c.mode); setModel(c.model); setProjectId(c.project_id || "");
    } catch (e: any) { toast(e.message); nav("/"); }
  }, [nav, toast]);

  useEffect(() => {
    if (id && skipLoad.current === id) { skipLoad.current = null; return; }
    abortRef.current?.(); setBusy(false); setArtifact(null);
    if (id) load(id);
    else { setConv(null); setMsgs([]); setAtts({}); setMode("chat"); setModel("auto"); }
  }, [id, load]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: busy ? "auto" : "smooth" }); }, [msgs, busy]);
  useEffect(() => {
    const ta = taRef.current;
    if (ta) { ta.style.height = "auto"; ta.style.height = Math.min(ta.scrollHeight, 280) + "px"; }
  }, [text]);

  const uploadFiles = async (files: FileList | File[]) => {
    const list = Array.from(files);
    if (!list.length) return;
    const fd = new FormData();
    list.forEach((f) => fd.append("files", f, f.name || "pasted.png"));
    setUploading(true);
    try {
      const res: Att[] = await api.upload("/files", fd);
      res.filter((r) => r.error).forEach((r) => toast(`${r.filename}: ${r.error}`));
      const ok = res.filter((r) => !r.error);
      setPending((p) => [...p, ...ok]);
      setAtts((a) => ({ ...a, ...Object.fromEntries(ok.map((r) => [r.id, r])) }));
      if (ok.some((r) => r.kind === "image") && mode === "chat") setMode("vision");
      ok.filter((r) => r.analysis?.error || r.analysis?.note).forEach((r) => toast(`${r.filename}: ${r.analysis.error || r.analysis.note}`, "info"));
    } catch (e: any) { toast(`Upload failed: ${e.message}`); }
    finally { setUploading(false); }
  };

  const send = async (regenerate = false) => {
    if (busy) return;
    const body = text.trim();
    if (!regenerate && !body && !pending.length) return;
    let cid = conv?.id;
    try {
      if (!cid) {
        const c = await api.post("/conversations", { project_id: projectId || null, mode, model });
        cid = c.id; setConv(c); skipLoad.current = c.id; nav(`/c/${c.id}`);
      } else if (conv.mode !== mode || conv.model !== model) {
        await api.patch(`/conversations/${cid}`, { mode, model });
        setConv({ ...conv, mode, model });
      }
    } catch (e: any) { toast(e.message); return; }
    const attIds = pending.map((p) => p.id);
    const tmpUser: Msg = { id: "u" + Date.now(), role: "user", content: body, attachments: attIds, meta: {} };
    const tmpAsst: Msg = { id: "a" + Date.now(), role: "assistant", content: "", attachments: [], meta: {}, streaming: true, thinking: "" };
    setMsgs((m) => {
      let base = m;
      if (regenerate) { base = [...m]; if (base.at(-1)?.role === "assistant") base.pop(); }
      return regenerate ? [...base, tmpAsst] : [...base, tmpUser, tmpAsst];
    });
    setText(""); setPending([]); setBusy(true);
    const update = (fn: (a: Msg) => Msg) => setMsgs((m) => m.map((x) => (x.id === tmpAsst.id ? fn(x) : x)));
    abortRef.current = stream(`/conversations/${cid}/chat`,
      { text: body, attachments: attIds, mode, model, web_search: web, regenerate },
      ({ event, data }) => {
        if (event === "meta") update((a) => ({ ...a, model: data.model, meta: { ...a.meta, memories: data.memories, documents: data.documents }, status: data.summarized ? "Summarised older messages to fit the context window" : a.status }));
        else if (event === "status") update((a) => ({ ...a, status: data.message }));
        else if (event === "citations") update((a) => ({ ...a, meta: { ...a.meta, citations: data } }));
        else if (event === "thinking") update((a) => ({ ...a, thinking: (a.thinking || "") + data.delta }));
        else if (event === "delta") update((a) => ({ ...a, content: a.content + data.delta, status: undefined }));
        else if (event === "title") { setConv((c: any) => (c ? { ...c, title: data.title } : c)); refreshConversations(); }
        else if (event === "error") update((a) => ({ ...a, streaming: false, meta: { ...a.meta, error: data.message } }));
        else if (event === "done") update((a) => ({ ...a, id: data.message_id, streaming: false, meta: { ...a.meta, stats: data.stats, error: data.error } }));
      },
      (err) => { update((a) => ({ ...a, streaming: false, meta: { ...a.meta, error: err.message } })); setBusy(false); },
      () => { update((a) => ({ ...a, streaming: false })); setBusy(false); refreshConversations(); },
    );
  };

  const stop = () => { abortRef.current?.(); setBusy(false); };
  const visionMissing = mode === "vision" && models?.roles?.vision && !models.roles.vision.installed && model === "auto";

  return (
    <div className="flex h-full">
      <div className="hidden lg:block"><Sidebar activeId={id} /></div>
      {mobileList && (
        <div className="fixed inset-0 z-40 flex lg:hidden" onClick={() => setMobileList(false)}>
          <div className="h-full bg-bg shadow-xl" onClick={(e) => e.stopPropagation()}><Sidebar activeId={id} onNavigate={() => setMobileList(false)} /></div>
          <div className="flex-1 bg-black/40" />
        </div>
      )}
      <div className={clsx("flex min-w-0 flex-1 flex-col", artifact && "hidden xl:flex")}
        onDragOver={(e) => { e.preventDefault(); setDrag(true); }} onDragLeave={() => setDrag(false)}
        onDrop={(e) => { e.preventDefault(); setDrag(false); uploadFiles(e.dataTransfer.files); }}>
        <header className="flex items-center gap-2 border-b border-border px-4 py-2">
          <button className="btn-ghost px-2 lg:hidden" onClick={() => setMobileList(true)} title="Conversations"><Menu size={16} /></button>
          <h1 className="min-w-0 flex-1 truncate font-medium">{conv?.title || "New chat"}</h1>
          {conv?.source && conv.source !== "kyro" && <span className="chip">imported from {conv.source}</span>}
          {conv && (
            <>
              <select className="input w-auto py-1 text-xs" value={conv.project_id || ""} title="Project"
                onChange={async (e) => { const v = e.target.value || null; await api.patch(`/conversations/${conv.id}`, { project_id: v }); setConv({ ...conv, project_id: v }); setProjectId(v || ""); refreshConversations(); }}>
                <option value="">No project</option>
                {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
              <button className="btn-ghost px-2" title="Export Markdown" onClick={() => download(`/conversations/${conv.id}/export`, { fmt: "md" })}><Download size={15} /></button>
            </>
          )}
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto max-w-3xl space-y-6 px-4 py-6">
            {!msgs.length && (
              <div className="pt-[12vh] text-center">
                <div className="mx-auto mb-4 grid h-14 w-14 place-items-center rounded-2xl bg-accent text-2xl font-bold text-white">K</div>
                <h2 className="font-serif text-3xl">How can I help you today?</h2>
                <p className="mt-2 text-sm text-muted">Local, private and unlimited. Your imported ChatGPT & Claude memories are used automatically.</p>
                <div className="mx-auto mt-6 flex max-w-md flex-col gap-2 text-left">
                  <label className="label">Project</label>
                  <select className="input" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
                    <option value="">No project (general memory only)</option>
                    {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                  </select>
                </div>
              </div>
            )}
            {msgs.map((m, i) => (
              <Message key={m.id} m={m} atts={atts} onPreview={setArtifact} isLast={i === msgs.length - 1 && !busy} onRegenerate={() => send(true)} />
            ))}
            <div ref={bottomRef} />
          </div>
        </div>
        <div className="px-4 pb-4">
          <div className={clsx("mx-auto max-w-3xl rounded-2xl border bg-panel shadow-sm transition-colors", drag ? "border-accent" : "border-border")}>
            {(pending.length > 0 || uploading) && (
              <div className="flex flex-wrap gap-1.5 px-3 pt-3">
                {pending.map((p) => <AttachmentChip key={p.id} a={p} onRemove={() => setPending(pending.filter((x) => x.id !== p.id))} />)}
                {uploading && <span className="chip"><Loader2 size={12} className="animate-spin" /> analysing…</span>}
              </div>
            )}
            <textarea ref={taRef} rows={1} value={text} onChange={(e) => setText(e.target.value)}
              placeholder={drag ? "Drop files to attach…" : "Message KYRO…  (Shift+Enter for a new line)"}
              className="block w-full resize-none bg-transparent px-4 py-3 outline-none"
              onPaste={(e) => { const f = Array.from(e.clipboardData.files); if (f.length) { e.preventDefault(); uploadFiles(f); } }}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); } }} />
            <div className="flex flex-wrap items-center gap-1.5 px-2 pb-2">
              <input ref={fileRef} type="file" multiple hidden onChange={(e) => { if (e.target.files) uploadFiles(e.target.files); e.target.value = ""; }} />
              <button className="btn-ghost px-2" title="Attach files (images, PDF, code, ZIP, JAR…)" onClick={() => fileRef.current?.click()}><Paperclip size={16} /></button>
              <select className="input w-auto py-1 text-xs" value={mode} onChange={(e) => setMode(e.target.value)} title="Mode">
                {MODES.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
              </select>
              <ModelSelect value={model} onChange={setModel} />
              <button className={clsx("btn px-2 py-1 text-xs", web ? "bg-accent/15 text-accent" : "text-muted hover:bg-panel2")} onClick={() => setWeb(!web)} title="Web search with citations">
                <Globe size={14} /> Web
              </button>
              {visionMissing && <span className="flex items-center gap-1 text-xs text-amber-600"><ImageIcon size={13} /> vision model not installed</span>}
              <span className="flex-1" />
              {busy ? (
                <button className="btn-outline" onClick={stop}><Square size={14} /> Stop</button>
              ) : (
                <button className="btn-primary h-8 w-8 justify-center p-0" disabled={!text.trim() && !pending.length} onClick={() => send()} title="Send"><ArrowUp size={16} /></button>
              )}
            </div>
          </div>
          <p className="mx-auto mt-1.5 max-w-3xl text-center text-[11px] text-muted">KYRO runs locally. Models can make mistakes — verify important information.</p>
        </div>
      </div>
      {artifact && <div className="w-full xl:w-[45%]"><ArtifactPanel artifact={artifact} onClose={() => setArtifact(null)} /></div>}
    </div>
  );
}

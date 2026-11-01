import { useCallback, useEffect, useState } from "react";
import clsx from "clsx";
import { AlertTriangle, Check, Download, Pencil, Pin, Plus, RefreshCw, Search, ShieldCheck, Trash2, X } from "lucide-react";
import { api, download } from "../lib/api";
import { useApp } from "../lib/store";

const KINDS = ["fact", "preference", "instruction", "project", "note", "document"];
const SOURCES = ["manual", "chatgpt", "claude", "chatgpt-memory", "claude-memory", "claude-project", "custom-instructions", "note", "kyro-chat", "project-scan", "seed"];

export default function MemoryPage() {
  const { projects, toast } = useApp();
  const [filters, setFilters] = useState<any>({ scope: "", project_id: "", status: "", source: "", kind: "", q: "" });
  const [data, setData] = useState<{ total: number; items: any[] }>({ total: 0, items: [] });
  const [stats, setStats] = useState<any>(null);
  const [semantic, setSemantic] = useState("");
  const [semHits, setSemHits] = useState<any[] | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [editText, setEditText] = useState("");
  const [adding, setAdding] = useState(false);
  const [newMem, setNewMem] = useState({ content: "", kind: "fact", scope: "general", project_id: "" });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(0);
  const PAGE = 100;

  const load = useCallback(async () => {
    try {
      const [d, s] = await Promise.all([api.get("/memories", { ...filters, limit: PAGE, offset: page * PAGE }), api.get("/memories/stats")]);
      setData(d); setStats(s);
    } catch (e: any) { toast(e.message); }
  }, [filters, page, toast]);
  useEffect(() => { const t = setTimeout(load, 200); return () => clearTimeout(t); }, [load]);

  const projName = (id: string) => projects.find((p) => p.id === id)?.name || "project";
  const save = async (id: string, patch: any) => {
    try { await api.patch(`/memories/${id}`, patch); setEditing(null); load(); } catch (e: any) { toast(e.message); }
  };
  const remove = async (id: string) => { if (!confirm("Delete this memory?")) return; await api.del(`/memories/${id}`); load(); };
  const bulkDelete = async () => {
    if (!confirm(`Delete ${selected.size} memories?`)) return;
    await api.post("/memories/bulk-delete", { ids: [...selected] }); setSelected(new Set()); load();
  };
  const add = async () => {
    try {
      const r = await api.post("/memories", { ...newMem, project_id: newMem.scope === "project" ? newMem.project_id : null });
      toast(r.status === "duplicate" ? "Already known — merged with existing memory." : r.status === "conflict" ? "Saved, but it conflicts with an existing memory. Review it below." : "Memory saved", r.status === "conflict" ? "info" : "success");
      setAdding(false); setNewMem({ ...newMem, content: "" }); load();
    } catch (e: any) { toast(e.message); }
  };
  const runSemantic = async () => {
    if (!semantic.trim()) { setSemHits(null); return; }
    try { setSemHits(await api.get("/memories/search", { q: semantic, project_id: filters.project_id || undefined })); } catch (e: any) { toast(e.message); }
  };
  const resolve = async (keep: string, other: string, action: string) => {
    try { await api.post("/memories/resolve", { keep_id: keep, other_id: other, action }); load(); } catch (e: any) { toast(e.message); }
  };
  const reembed = async () => {
    try { const r = await api.post("/memories/reembed"); toast(`Re-embedded ${r.reembedded} memories with ${r.model}`, "success"); load(); } catch (e: any) { toast(e.message); }
  };

  const list = semHits ?? data.items;

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-5xl space-y-4 p-4 md:p-6">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="font-serif text-2xl">Memory</h1>
          <span className="text-sm text-muted">{stats?.total ?? 0} memories · stored locally</span>
          <span className="flex-1" />
          <button className="btn-outline" onClick={() => setAdding(!adding)}><Plus size={15} /> Add</button>
          <button className="btn-ghost" onClick={() => download("/memories/export", { fmt: "json" })}><Download size={15} /> JSON</button>
          <button className="btn-ghost" onClick={() => download("/memories/export", { fmt: "md" })}><Download size={15} /> Markdown</button>
          <button className="btn-ghost" onClick={reembed} title="Recompute embeddings (after changing the embedding model)"><RefreshCw size={15} /> Re-embed</button>
        </div>
        {stats && (
          <div className="flex flex-wrap gap-1.5 text-xs">
            {Object.entries(stats.by_source).map(([k, v]) => <span key={k} className="chip">{k}: {v as number}</span>)}
            {stats.by_status.conflict ? <button className="chip border-amber-500/50 text-amber-600" onClick={() => setFilters({ ...filters, status: "conflict" })}><AlertTriangle size={12} /> {stats.by_status.conflict} conflicts</button> : null}
            {Object.entries(stats.embedding_models).map(([k, v]) => <span key={k} className="chip" title="Embedding model">{k === "hash-lex-512" ? "lexical fallback" : k}: {v as number}</span>)}
          </div>
        )}
        {adding && (
          <div className="card space-y-2 p-3">
            <textarea className="input min-h-[70px]" placeholder="Something KYRO should remember…" value={newMem.content} onChange={(e) => setNewMem({ ...newMem, content: e.target.value })} />
            <div className="flex flex-wrap gap-2">
              <select className="input w-auto" value={newMem.kind} onChange={(e) => setNewMem({ ...newMem, kind: e.target.value })}>{KINDS.map((k) => <option key={k}>{k}</option>)}</select>
              <select className="input w-auto" value={newMem.scope} onChange={(e) => setNewMem({ ...newMem, scope: e.target.value })}><option value="general">General</option><option value="project">Project</option></select>
              {newMem.scope === "project" && (
                <select className="input w-auto" value={newMem.project_id} onChange={(e) => setNewMem({ ...newMem, project_id: e.target.value })}>
                  <option value="">Choose project…</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              )}
              <span className="flex-1" />
              <button className="btn-primary" onClick={add} disabled={!newMem.content.trim()}>Save</button>
            </div>
          </div>
        )}
        <div className="card flex flex-wrap items-center gap-2 p-2">
          <div className="relative min-w-[200px] flex-1">
            <Search size={14} className="absolute left-2.5 top-2.5 text-muted" />
            <input className="input pl-8" placeholder="Semantic search (what KYRO would retrieve)…" value={semantic} onChange={(e) => setSemantic(e.target.value)} onKeyDown={(e) => e.key === "Enter" && runSemantic()} />
          </div>
          <button className="btn-outline" onClick={runSemantic}>Search</button>
          {semHits && <button className="btn-ghost" onClick={() => { setSemHits(null); setSemantic(""); }}><X size={14} /> Clear</button>}
        </div>
        {!semHits && (
          <div className="flex flex-wrap gap-2">
            <input className="input w-48" placeholder="Filter text…" value={filters.q} onChange={(e) => { setPage(0); setFilters({ ...filters, q: e.target.value }); }} />
            <select className="input w-auto" value={filters.scope} onChange={(e) => setFilters({ ...filters, scope: e.target.value })}><option value="">All scopes</option><option value="general">General</option><option value="project">Project</option></select>
            <select className="input w-auto" value={filters.project_id} onChange={(e) => setFilters({ ...filters, project_id: e.target.value })}><option value="">All projects</option>{projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
            <select className="input w-auto" value={filters.source} onChange={(e) => setFilters({ ...filters, source: e.target.value })}><option value="">All sources</option>{SOURCES.map((s) => <option key={s}>{s}</option>)}</select>
            <select className="input w-auto" value={filters.kind} onChange={(e) => setFilters({ ...filters, kind: e.target.value })}><option value="">All kinds</option>{KINDS.map((s) => <option key={s}>{s}</option>)}</select>
            <select className="input w-auto" value={filters.status} onChange={(e) => setFilters({ ...filters, status: e.target.value })}><option value="">Any status</option>{["active", "conflict", "superseded", "archived"].map((s) => <option key={s}>{s}</option>)}</select>
            {selected.size > 0 && <button className="btn-danger" onClick={bulkDelete}><Trash2 size={14} /> Delete {selected.size}</button>}
          </div>
        )}
        <div className="space-y-2">
          {list.map((m: any) => (
            <div key={m.id} className={clsx("card p-3", m.status === "conflict" && "border-amber-500/50", m.status === "superseded" && "opacity-60")}>
              <div className="flex items-start gap-2">
                {!semHits && <input type="checkbox" className="mt-1" checked={selected.has(m.id)} onChange={(e) => { const s = new Set(selected); e.target.checked ? s.add(m.id) : s.delete(m.id); setSelected(s); }} />}
                <div className="min-w-0 flex-1">
                  {editing === m.id ? (
                    <div className="space-y-2">
                      <textarea className="input min-h-[60px]" value={editText} onChange={(e) => setEditText(e.target.value)} />
                      <div className="flex gap-2"><button className="btn-primary" onClick={() => save(m.id, { content: editText })}><Check size={14} /> Save</button><button className="btn-ghost" onClick={() => setEditing(null)}>Cancel</button></div>
                    </div>
                  ) : <div className="whitespace-pre-wrap text-sm">{m.content}</div>}
                  <div className="mt-1.5 flex flex-wrap items-center gap-1 text-[11px] text-muted">
                    <span className="chip">{m.kind}</span>
                    <span className="chip">{m.scope === "project" ? projName(m.project_id) : "general"}</span>
                    <span title={m.source_ref}>from <b>{m.source}</b>{m.source_time ? ` · ${new Date(m.source_time * 1000).toLocaleDateString()}` : ""}</span>
                    {m.extra_sources?.length > 0 && <span title={m.extra_sources.map((s: any) => s.source).join(", ")}>· also in {m.extra_sources.length} other source(s)</span>}
                    {m.verified && <span className="flex items-center gap-0.5 text-emerald-600"><ShieldCheck size={12} /> verified</span>}
                    {m.status !== "active" && <span className="chip">{m.status}</span>}
                    {m.score !== undefined && <span className="chip">score {m.score}</span>}
                  </div>
                  {m.status === "conflict" && m.conflicts_with?.length > 0 && (
                    <ConflictBox id={m.id} others={m.conflicts_with} onResolve={resolve} />
                  )}
                </div>
                <div className="flex shrink-0 gap-0.5">
                  <button className={clsx("btn-ghost p-1.5", m.pinned && "text-accent")} onClick={() => save(m.id, { pinned: !m.pinned })} title="Pin (always preferred)"><Pin size={14} /></button>
                  <button className="btn-ghost p-1.5" onClick={() => save(m.id, { verified: !m.verified })} title="Toggle verified"><ShieldCheck size={14} /></button>
                  <button className="btn-ghost p-1.5" onClick={() => { setEditing(m.id); setEditText(m.content); }} title="Edit"><Pencil size={14} /></button>
                  <button className="btn-danger p-1.5" onClick={() => remove(m.id)} title="Delete"><Trash2 size={14} /></button>
                </div>
              </div>
            </div>
          ))}
          {!list.length && <div className="py-10 text-center text-sm text-muted">No memories. Import your ChatGPT/Claude data on the Import page, or add one manually.</div>}
        </div>
        {!semHits && data.total > PAGE && (
          <div className="flex items-center justify-center gap-2">
            <button className="btn-outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span className="text-sm text-muted">{page * PAGE + 1}–{Math.min(data.total, (page + 1) * PAGE)} of {data.total}</span>
            <button className="btn-outline" disabled={(page + 1) * PAGE >= data.total} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        )}
      </div>
    </div>
  );
}

function ConflictBox({ id, others, onResolve }: { id: string; others: string[]; onResolve: (k: string, o: string, a: string) => void }) {
  const [items, setItems] = useState<any[]>([]);
  useEffect(() => { Promise.all(others.map((o) => api.get(`/memories/${o}`).catch(() => null))).then((r) => setItems(r.filter(Boolean))); }, [others]);
  return (
    <div className="mt-2 space-y-1.5 rounded-lg border border-amber-500/40 bg-amber-500/5 p-2 text-xs">
      <div className="flex items-center gap-1 font-medium text-amber-700 dark:text-amber-300"><AlertTriangle size={12} /> Possible contradiction</div>
      {items.map((o) => (
        <div key={o.id} className="flex flex-wrap items-center gap-2">
          <span className="min-w-0 flex-1">“{o.content}” <span className="text-muted">({o.source}{o.source_time ? `, ${new Date(o.source_time * 1000).toLocaleDateString()}` : ""})</span></span>
          <button className="btn-outline px-2 py-0.5 text-xs" onClick={() => onResolve(id, o.id, "keep_one")}>Keep this one</button>
          <button className="btn-outline px-2 py-0.5 text-xs" onClick={() => onResolve(o.id, id, "keep_one")}>Keep other</button>
          <button className="btn-ghost px-2 py-0.5 text-xs" onClick={() => onResolve(id, o.id, "keep_both")}>Both true</button>
        </div>
      ))}
    </div>
  );
}

import { ReactNode, useEffect, useState } from "react";
import { CheckCircle2, CircleAlert, DownloadCloud, Loader2, Save } from "lucide-react";
import { api, getToken, setToken, stream } from "../lib/api";
import { useApp } from "../lib/store";

const ROLE_HELP: Record<string, string> = {
  chat: "General conversation", reasoning: "Reasoning mode / hard problems", coding: "Coding, agent & project mode",
  vision: "Images & screenshots", embedding: "Semantic memory search", summarize: "Long-context summaries", fast: "Titles & memory extraction",
};
const SUGGESTED = ["qwen3:14b", "qwen3:8b", "qwen3:4b", "qwen2.5-coder:14b", "qwen2.5-coder:7b", "deepseek-r1:14b", "qwen2.5vl:7b", "llama3.2-vision:11b", "gemma3:12b", "nomic-embed-text", "bge-m3"];

function Section({ title, children, desc }: { title: string; desc?: string; children: ReactNode }) {
  return (
    <section className="card space-y-3 p-4">
      <div><h2 className="font-medium">{title}</h2>{desc && <p className="text-xs text-muted">{desc}</p>}</div>
      {children}
    </section>
  );
}

export default function SettingsPage() {
  const { toast, models, refreshModels, refreshHealth, health } = useApp();
  const [s, setS] = useState<any>(null);
  const [saving, setSaving] = useState(false);
  const [pull, setPull] = useState("");
  const [pulling, setPulling] = useState<{ model: string; status: string; pct: number } | null>(null);
  const [token, setTok] = useState(getToken());
  const [newPersona, setNewPersona] = useState("");

  useEffect(() => { api.get("/settings").then(setS).catch((e) => toast(e.message)); }, [toast]);
  if (!s) return <div className="p-6 text-sm text-muted">Loading settings…</div>;

  const set = (path: string, value: any) => {
    const next = structuredClone(s);
    const keys = path.split(".");
    let o = next;
    keys.slice(0, -1).forEach((k) => (o = o[k]));
    o[keys.at(-1)!] = value;
    setS(next);
  };
  const save = async () => {
    setSaving(true);
    try {
      const out = await api.patch("/settings", s);
      setS(out); toast("Settings saved", "success"); refreshModels(); refreshHealth();
    } catch (e: any) { toast(e.message); } finally { setSaving(false); }
  };
  const doPull = (model: string) => {
    if (!model) return;
    setPulling({ model, status: "starting", pct: 0 });
    stream("/models/pull", { model }, ({ event, data }) => {
      if (event === "progress") setPulling({ model, status: data.status, pct: data.total ? Math.round((data.completed || 0) / data.total * 100) : 0 });
      if (event === "error") { toast(`Pull ${model}: ${data.message}`); setPulling(null); }
      if (event === "done") { toast(`${model} installed`, "success"); setPulling(null); refreshModels(); }
    }, (e) => { toast(e.message); setPulling(null); });
  };
  const installed = new Set((models?.ollama || []).map((m: any) => m.name));

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl space-y-4 p-4 md:p-6">
        <div className="flex items-center gap-2">
          <h1 className="font-serif text-2xl">Settings</h1>
          <span className="flex-1" />
          <button className="btn-primary" onClick={save} disabled={saving}>{saving ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />} Save</button>
        </div>

        <Section title="Local inference (Ollama)" desc="No message limits, no subscriptions. Speed and context are limited only by your hardware.">
          <div className="flex items-center gap-2 text-sm">
            {health?.ollama?.ok ? <><CheckCircle2 size={16} className="text-emerald-500" /> Connected · Ollama {health.ollama.version}</> : <><CircleAlert size={16} className="text-red-500" /> {health?.ollama?.error || "Not connected"} — install from ollama.com and run it.</>}
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1"><span className="label">Ollama URL</span><input className="input" value={s.ollama_url} onChange={(e) => set("ollama_url", e.target.value)} /></label>
            <label className="space-y-1"><span className="label">Context window (num_ctx tokens)</span><input type="number" className="input" value={s.num_ctx} onChange={(e) => set("num_ctx", +e.target.value)} /></label>
          </div>
          <div className="label pt-2">Model per task (Auto routing)</div>
          <div className="grid gap-2 md:grid-cols-2">
            {Object.entries(s.models).map(([role, name]) => {
              const ok = installed.has(name as string) || installed.has(`${name}:latest`);
              return (
                <div key={role} className="flex items-center gap-2">
                  <div className="w-24 shrink-0 text-sm" title={ROLE_HELP[role]}>{role}</div>
                  <input className="input py-1 font-mono text-xs" list="model-list" value={name as string} onChange={(e) => set(`models.${role}`, e.target.value)} />
                  {ok ? <CheckCircle2 size={16} className="shrink-0 text-emerald-500" /> : (
                    <button className="btn-outline shrink-0 px-2 py-1 text-xs" onClick={() => doPull(name as string)} disabled={!!pulling} title="Download with Ollama"><DownloadCloud size={13} /> Pull</button>
                  )}
                </div>
              );
            })}
          </div>
          <datalist id="model-list">{[...installed, ...SUGGESTED].map((m) => <option key={m as string} value={m as string} />)}</datalist>
          <div className="flex items-center gap-2 pt-2">
            <input className="input py-1 font-mono text-xs" list="model-list" placeholder="any model, e.g. qwen2.5-coder:14b" value={pull} onChange={(e) => setPull(e.target.value)} />
            <button className="btn-outline" onClick={() => doPull(pull)} disabled={!pull || !!pulling}><DownloadCloud size={15} /> Download</button>
          </div>
          {pulling && (
            <div className="text-xs">
              <div className="mb-1">{pulling.model}: {pulling.status} {pulling.pct ? `${pulling.pct}%` : ""}</div>
              <div className="h-1.5 rounded bg-panel2"><div className="h-1.5 rounded bg-accent transition-all" style={{ width: `${pulling.pct}%` }} /></div>
            </div>
          )}
          {models?.errors?.length > 0 && <div className="text-xs text-red-600">{models.errors.join(" · ")}</div>}
        </Section>

        <Section title="Cloud models (optional)" desc="Any OpenAI-compatible endpoint (FreeLLMAPI, OpenRouter, LM Studio, vLLM…). Never required.">
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.cloud.enabled} onChange={(e) => set("cloud.enabled", e.target.checked)} /> Enable</label>
          <div className="grid gap-3 md:grid-cols-2">
            <input className="input" placeholder="https://host/v1" value={s.cloud.base_url} onChange={(e) => set("cloud.base_url", e.target.value)} />
            <input className="input" type="password" placeholder="API key" value={s.cloud.api_key} onChange={(e) => set("cloud.api_key", e.target.value)} />
          </div>
        </Section>

        <Section title="Personality & custom instructions">
          <div className="flex flex-wrap items-center gap-2">
            <span className="label">Active</span>
            <select className="input w-auto" value={s.active_personality} onChange={(e) => set("active_personality", e.target.value)}>
              {Object.keys(s.personalities).map((p) => <option key={p}>{p}</option>)}
            </select>
            <input className="input w-40" placeholder="new personality" value={newPersona} onChange={(e) => setNewPersona(e.target.value)} />
            <button className="btn-outline" disabled={!newPersona.trim()} onClick={() => { set(`personalities.${newPersona.trim()}`, "You are KYRO AI. "); setNewPersona(""); }}>Add</button>
          </div>
          <textarea className="input min-h-[80px] text-sm" value={s.personalities[s.active_personality] || ""} onChange={(e) => set(`personalities.${s.active_personality}`, e.target.value)} />
          <label className="block space-y-1"><span className="label">Custom instructions (always applied)</span>
            <textarea className="input min-h-[100px] text-sm" placeholder="e.g. Answer in German unless I write in English. I use Windows 11, Java 21, IntelliJ." value={s.custom_instructions} onChange={(e) => set("custom_instructions", e.target.value)} />
          </label>
        </Section>

        <Section title="Memory">
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.memory.auto_retrieve} onChange={(e) => set("memory.auto_retrieve", e.target.checked)} /> Automatically retrieve relevant memories in every chat</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.memory.auto_extract_from_chats} onChange={(e) => set("memory.auto_extract_from_chats", e.target.checked)} /> Learn new facts from my KYRO chats (verbatim first-person statements only)</label>
          <div className="grid gap-3 md:grid-cols-4">
            <label className="space-y-1"><span className="label">Top-K</span><input type="number" className="input" value={s.memory.top_k} onChange={(e) => set("memory.top_k", +e.target.value)} /></label>
            <label className="space-y-1"><span className="label">Min score</span><input type="number" step="0.05" className="input" value={s.memory.min_score} onChange={(e) => set("memory.min_score", +e.target.value)} /></label>
            <label className="space-y-1"><span className="label">Duplicate ≥</span><input type="number" step="0.01" className="input" value={s.memory.duplicate_threshold} onChange={(e) => set("memory.duplicate_threshold", +e.target.value)} /></label>
            <label className="space-y-1"><span className="label">Conflict ≥</span><input type="number" step="0.01" className="input" value={s.memory.conflict_threshold} onChange={(e) => set("memory.conflict_threshold", +e.target.value)} /></label>
          </div>
          <p className="text-xs text-muted">Active embedding model: <b>{health?.embedding_model}</b>{health?.embedding_model === "hash-lex-512" && " (lexical fallback — pull the embedding model above for true semantic search, then press Re-embed on the Memory page)"}</p>
        </Section>

        <Section title="Coding agent & sandbox" desc="The agent can only access its workspace. Commands not on this list — and anything destructive — wait for your approval.">
          <label className="block space-y-1"><span className="label">Auto-approved command prefixes (one per line)</span>
            <textarea className="input min-h-[120px] font-mono text-xs" value={s.agent.auto_approve_commands.join("\n")} onChange={(e) => set("agent.auto_approve_commands", e.target.value.split("\n").map((x) => x.trim()).filter(Boolean))} />
          </label>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1"><span className="label">Max steps per run</span><input type="number" className="input" value={s.agent.max_steps} onChange={(e) => set("agent.max_steps", +e.target.value)} /></label>
            <label className="space-y-1"><span className="label">Command timeout (s)</span><input type="number" className="input" value={s.agent.command_timeout} onChange={(e) => set("agent.command_timeout", +e.target.value)} /></label>
          </div>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.agent.require_approval_for_writes} onChange={(e) => set("agent.require_approval_for_writes", e.target.checked)} /> Ask before every file write</label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.agent.require_approval_for_deletes} onChange={(e) => set("agent.require_approval_for_deletes", e.target.checked)} /> Ask before deleting files</label>
          <label className="block space-y-1"><span className="label">Workspace root</span><input className="input font-mono text-xs" placeholder={s.workspace_root_resolved} value={s.workspace_root} onChange={(e) => set("workspace_root", e.target.value)} /></label>
        </Section>

        <Section title="Web search & image generation">
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.web_search_enabled} onChange={(e) => set("web_search_enabled", e.target.checked)} /> Allow web search (only when you enable “Web” or use Research mode)</label>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1"><span className="label">SearXNG URL (optional, else DuckDuckGo)</span><input className="input" placeholder="http://127.0.0.1:8080" value={s.searxng_url} onChange={(e) => set("searxng_url", e.target.value)} /></label>
            <label className="space-y-1"><span className="label">ComfyUI URL (optional)</span><input className="input" placeholder="http://127.0.0.1:8188" value={s.comfyui_url} onChange={(e) => set("comfyui_url", e.target.value)} /></label>
          </div>
        </Section>

        <Section title="Security" desc="KYRO listens on 127.0.0.1 by default. Enable an access token before exposing it on your network.">
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.auth.enabled} onChange={(e) => set("auth.enabled", e.target.checked)} /> Require access token</label>
          <div className="flex flex-wrap items-center gap-2">
            <input className="input max-w-sm font-mono text-xs" placeholder="token for this browser" value={token} onChange={(e) => setTok(e.target.value)} />
            <button className="btn-outline" onClick={() => { setToken(token); toast("Token stored in this browser", "success"); }}>Use token</button>
            <button className="btn-ghost" onClick={async () => { try { const r = await api.get("/settings/token"); setTok(r.token); setToken(r.token); } catch (e: any) { toast(e.message); } }}>Reveal (local only)</button>
          </div>
          <p className="text-xs text-muted">Data folder: <code>{s.data_dir}</code></p>
        </Section>
      </div>
    </div>
  );
}

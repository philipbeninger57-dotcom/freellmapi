import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Editor, { DiffEditor } from "@monaco-editor/react";
import clsx from "clsx";
import {
  Bot, Check, ChevronDown, ChevronRight, CircleAlert, CircleCheck, CircleDashed, Download, File, FilePlus, Folder,
  Loader2, Play, RefreshCw, Save, ShieldAlert, Square, Terminal as TermIcon, Trash2, X,
} from "lucide-react";
import "../components/monaco";
import { languageFor } from "../components/monaco";
import { api, download, stream } from "../lib/api";
import { useApp } from "../lib/store";
import ModelSelect from "../components/ModelSelect";

type Entry = { path: string; dir: boolean; size: number };
type TreeNode = { name: string; path: string; dir: boolean; children: TreeNode[] };
const PHASES = ["PLAN", "READ", "IMPLEMENT", "TEST", "DEBUG", "VERIFY", "DELIVER"];

function buildTree(entries: Entry[]): TreeNode[] {
  const root: TreeNode = { name: "", path: "", dir: true, children: [] };
  const map = new Map<string, TreeNode>([["", root]]);
  for (const e of entries) {
    const parts = e.path.split("/");
    const parent = map.get(parts.slice(0, -1).join("/")) || root;
    const node: TreeNode = { name: parts.at(-1)!, path: e.path, dir: e.dir, children: [] };
    parent.children.push(node);
    if (e.dir) map.set(e.path, node);
  }
  const sort = (n: TreeNode) => { n.children.sort((a, b) => (a.dir === b.dir ? a.name.localeCompare(b.name) : a.dir ? -1 : 1)); n.children.forEach(sort); };
  sort(root);
  return root.children;
}

function Tree({ nodes, open, setOpen, active, onOpen, depth = 0 }: any) {
  return nodes.map((n: TreeNode) => (
    <div key={n.path}>
      <button onClick={() => (n.dir ? setOpen({ ...open, [n.path]: !open[n.path] }) : onOpen(n.path))}
        className={clsx("flex w-full items-center gap-1 truncate rounded px-1 py-0.5 text-left text-[13px] hover:bg-panel2", active === n.path && "bg-panel2 text-accent")}
        style={{ paddingLeft: depth * 12 + 4 }}>
        {n.dir ? (open[n.path] ? <ChevronDown size={13} /> : <ChevronRight size={13} />) : <span className="w-[13px]" />}
        {n.dir ? <Folder size={13} className="text-accent/80" /> : <File size={13} className="text-muted" />}
        <span className="truncate">{n.name}</span>
      </button>
      {n.dir && open[n.path] && <Tree nodes={n.children} open={open} setOpen={setOpen} active={active} onOpen={onOpen} depth={depth + 1} />}
    </div>
  ));
}

function VerificationBadge({ v }: { v: any }) {
  if (!v) return null;
  const s = v.status;
  const cls = s === "verified" ? "text-emerald-600 border-emerald-500/40 bg-emerald-500/10" : s === "failing" ? "text-red-600 border-red-500/40 bg-red-500/10" : "text-amber-600 border-amber-500/40 bg-amber-500/10";
  const Icon = s === "verified" ? CircleCheck : s === "failing" ? CircleAlert : CircleDashed;
  const label = { verified: "Verified by tests", failing: "Tests failing", stale: "Changed after last test", not_verified: "Not verified" }[s as string] || s;
  return <span className={clsx("inline-flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs", cls)} title={v.detail}><Icon size={13} />{label}</span>;
}

export default function CodePage() {
  const { projects, toast, theme } = useApp();
  const [projectId, setProjectId] = useState<string>("");
  const [tree, setTree] = useState<{ root: string; entries: Entry[] } | null>(null);
  const [open, setOpen] = useState<Record<string, boolean>>({});
  const [file, setFile] = useState<{ path: string; content: string; original: string; binary?: boolean } | null>(null);
  const [termLines, setTermLines] = useState<{ cmd: string; out: string; code: number | null }[]>([]);
  const [cmd, setCmd] = useState("");
  const [termBusy, setTermBusy] = useState(false);
  const [bottom, setBottom] = useState<"terminal" | "agent">("agent");
  // agent
  const [task, setTask] = useState("");
  const [agentModel, setAgentModel] = useState("auto");
  const [scratch, setScratch] = useState("scratch");
  const [run, setRun] = useState<any>(null);
  const [events, setEvents] = useState<any[]>([]);
  const [diffs, setDiffs] = useState<any[] | null>(null);
  const [diffSel, setDiffSel] = useState(0);
  const stopStream = useRef<() => void>();
  const logRef = useRef<HTMLDivElement>(null);

  const wsParams = useMemo(() => (projectId ? { project_id: projectId } : {}), [projectId]);
  const loadTree = useCallback(async () => {
    try { setTree(await api.get("/workspace/tree", wsParams)); } catch (e: any) { toast(e.message); }
  }, [wsParams, toast]);
  useEffect(() => { setFile(null); loadTree(); }, [loadTree]);
  useEffect(() => { logRef.current?.scrollTo(0, logRef.current.scrollHeight); }, [events, termLines]);

  const openFile = async (path: string) => {
    if (file && file.content !== file.original && !confirm("Discard unsaved changes?")) return;
    try {
      const f = await api.get("/workspace/file", { path, ...wsParams });
      setFile({ path, content: f.content ?? "", original: f.content ?? "", binary: f.binary });
    } catch (e: any) { toast(e.message); }
  };
  const save = useCallback(async () => {
    if (!file) return;
    try { await api.put("/workspace/file", { path: file.path, content: file.content, ...wsParams }); setFile({ ...file, original: file.content }); toast("Saved", "success"); }
    catch (e: any) { toast(e.message); }
  }, [file, wsParams, toast]);
  const newFile = async () => {
    const p = prompt("New file path (relative to workspace):");
    if (!p) return;
    try { await api.put("/workspace/file", { path: p, content: "", ...wsParams }); await loadTree(); openFile(p); } catch (e: any) { toast(e.message); }
  };
  const delFile = async () => {
    if (!file || !confirm(`Delete ${file.path}?`)) return;
    try { await api.del("/workspace/file", { path: file.path, ...wsParams }); setFile(null); loadTree(); } catch (e: any) { toast(e.message); }
  };

  useEffect(() => {
    const h = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key === "s") { e.preventDefault(); save(); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [save]);

  const runTerminal = async () => {
    const c = cmd.trim();
    if (!c) return;
    setCmd(""); setTermBusy(true);
    setTermLines((l) => [...l, { cmd: c, out: "", code: null }]);
    try {
      const r = await api.post("/workspace/terminal", { command: c, ...wsParams });
      setTermLines((l) => [...l.slice(0, -1), { cmd: c, out: (r.stdout || "") + (r.stderr ? `\n${r.stderr}` : ""), code: r.exit_code }]);
      loadTree();
    } catch (e: any) {
      setTermLines((l) => [...l.slice(0, -1), { cmd: c, out: e.message, code: -1 }]);
    } finally { setTermBusy(false); }
  };

  const subscribe = (runId: string) => {
    stopStream.current?.();
    setEvents([]);
    stopStream.current = stream(`/agent/runs/${runId}/events`, null, ({ event, data }) => {
      if (event === "end") return;
      setEvents((e) => (event === "token" && e.at(-1)?.type === "token" ? [...e.slice(0, -1), { ...e.at(-1), delta: e.at(-1).delta + data.delta }] : [...e, data]));
      setRun((r: any) => {
        if (!r) return r;
        const n = { ...r };
        if (event === "phase") n.phase = data.phase;
        if (event === "plan") n.plan = data.steps;
        if (event === "approval_request") { n.pending = data; n.status = "waiting_approval"; }
        if (event === "approval_result") { n.pending = null; n.status = "running"; }
        if (event === "verification") n.verification = data;
        if (event === "finish") { n.status = data.status; n.summary = data.summary; n.verification = data.verification; n.changed_files = data.changed_files; }
        if (event === "file_changed") loadTree();
        return n;
      });
    }, (e) => toast(`Agent stream: ${e.message}`));
  };
  useEffect(() => () => stopStream.current?.(), []);

  const startAgent = async () => {
    if (!task.trim()) return;
    try {
      const r = await api.post("/agent/runs", { task, project_id: projectId || null, workspace: projectId ? null : scratch, model: agentModel });
      setRun(r); setDiffs(null); setBottom("agent"); subscribe(r.id);
    } catch (e: any) { toast(e.message); }
  };
  const approve = async (ok: boolean) => { try { await api.post(`/agent/runs/${run.id}/approve`, { approved: ok }); } catch (e: any) { toast(e.message); } };
  const cancel = async () => { try { await api.post(`/agent/runs/${run.id}/cancel`); } catch (e: any) { toast(e.message); } };
  const showDiffs = async () => { try { setDiffs(await api.get(`/agent/runs/${run.id}/diff`)); setDiffSel(0); } catch (e: any) { toast(e.message); } };

  const nodes = useMemo(() => buildTree(tree?.entries || []), [tree]);
  const running = run && ["running", "waiting_approval"].includes(run.status);
  const dirty = file && file.content !== file.original;

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-center gap-2 border-b border-border px-3 py-2">
        <select className="input w-auto py-1 text-sm" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
          <option value="">KYRO workspace</option>
          {projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <span className="truncate text-xs text-muted" title={tree?.root}>{tree?.root}</span>
        <span className="flex-1" />
        <button className="btn-ghost" onClick={loadTree} title="Refresh"><RefreshCw size={15} /></button>
        <button className="btn-ghost" onClick={newFile}><FilePlus size={15} /> New file</button>
        <button className="btn-ghost" onClick={() => download("/workspace/download", wsParams as any)}><Download size={15} /> Download ZIP</button>
      </header>
      <div className="flex min-h-0 flex-1">
        <div className="w-60 shrink-0 overflow-y-auto border-r border-border p-1">
          {nodes.length ? <Tree nodes={nodes} open={open} setOpen={setOpen} active={file?.path} onOpen={openFile} /> : <div className="p-3 text-xs text-muted">Empty workspace. Create a file, import a project ZIP, or let the agent build something.</div>}
        </div>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex items-center gap-2 border-b border-border px-3 py-1 text-sm">
            <span className="truncate">{file ? file.path : diffs ? "Agent changes" : "No file open"}{dirty && " •"}</span>
            <span className="flex-1" />
            {file && !file.binary && <button className="btn-ghost px-2 py-1" onClick={save} disabled={!dirty}><Save size={14} /> Save</button>}
            {file && <button className="btn-danger px-2 py-1" onClick={delFile}><Trash2 size={14} /></button>}
            {diffs && <button className="btn-ghost px-2 py-1" onClick={() => setDiffs(null)}><X size={14} /> Close diff</button>}
          </div>
          <div className="min-h-0 flex-1">
            {diffs ? (
              <div className="flex h-full">
                <div className="w-52 shrink-0 overflow-y-auto border-r border-border text-xs">
                  {diffs.map((d, i) => (
                    <button key={d.path} onClick={() => setDiffSel(i)} className={clsx("block w-full truncate px-2 py-1 text-left hover:bg-panel2", i === diffSel && "bg-panel2")}>
                      <span className={clsx("mr-1 font-mono", d.status === "added" ? "text-emerald-500" : d.status === "deleted" ? "text-red-500" : "text-amber-500")}>{d.status[0].toUpperCase()}</span>{d.path}
                    </button>
                  ))}
                  {!diffs.length && <div className="p-2 text-muted">No file changes.</div>}
                </div>
                {diffs[diffSel] && (
                  <DiffEditor key={diffs[diffSel].path} theme={theme === "dark" ? "vs-dark" : "vs"} language={languageFor(diffs[diffSel].path)}
                    original={diffs[diffSel].before ?? ""} modified={diffs[diffSel].after ?? ""} options={{ readOnly: true, renderSideBySide: true, minimap: { enabled: false } }} />
                )}
              </div>
            ) : file?.binary ? (
              <div className="p-6 text-sm text-muted">Binary file — not editable.</div>
            ) : file ? (
              <Editor key={file.path} theme={theme === "dark" ? "vs-dark" : "vs"} path={file.path} language={languageFor(file.path)} value={file.content}
                onChange={(v) => setFile((f) => (f ? { ...f, content: v ?? "" } : f))} options={{ fontSize: 13, minimap: { enabled: true }, tabSize: 4, automaticLayout: true }} />
            ) : (
              <div className="grid h-full place-items-center text-sm text-muted">Select a file from the explorer.</div>
            )}
          </div>
          {/* bottom panel */}
          <div className="flex h-[42%] min-h-[220px] flex-col border-t border-border">
            <div className="flex items-center gap-1 border-b border-border px-2 py-1">
              <button className={clsx("btn-ghost px-2 py-1 text-xs", bottom === "agent" && "bg-panel2")} onClick={() => setBottom("agent")}><Bot size={14} /> Agent</button>
              <button className={clsx("btn-ghost px-2 py-1 text-xs", bottom === "terminal" && "bg-panel2")} onClick={() => setBottom("terminal")}><TermIcon size={14} /> Terminal</button>
              {run && (
                <div className="ml-3 flex items-center gap-0.5 overflow-x-auto">
                  {PHASES.map((p) => {
                    const visited = p === "PLAN" || events.some((e) => e.type === "phase" && e.phase === p);
                    return <span key={p} title={visited ? "visited" : "not visited"} className={clsx("rounded px-1.5 py-0.5 text-[10px] font-semibold", p === run.phase ? "bg-accent text-white" : visited ? "text-accent" : "text-muted/50")}>{p}</span>;
                  })}
                </div>
              )}
              <span className="flex-1" />
              {run && <VerificationBadge v={run.verification} />}
            </div>
            {bottom === "terminal" ? (
              <div className="flex min-h-0 flex-1 flex-col bg-[#1e1e1e] font-mono text-xs text-neutral-200">
                <div ref={logRef} className="min-h-0 flex-1 overflow-y-auto p-2">
                  <div className="text-neutral-500">Commands run in {tree?.root}. Destructive system commands are blocked.</div>
                  {termLines.map((l, i) => (
                    <div key={i} className="mt-1">
                      <div className="text-emerald-400">$ {l.cmd}</div>
                      <pre className="whitespace-pre-wrap">{l.out}</pre>
                      {l.code !== null && <div className={l.code === 0 ? "text-neutral-500" : "text-red-400"}>[exit {l.code}]</div>}
                    </div>
                  ))}
                </div>
                <div className="flex items-center gap-2 border-t border-neutral-700 px-2 py-1">
                  <span className="text-emerald-400">$</span>
                  <input className="flex-1 bg-transparent outline-none" value={cmd} onChange={(e) => setCmd(e.target.value)} disabled={termBusy}
                    onKeyDown={(e) => e.key === "Enter" && runTerminal()} placeholder="mvn -q package · gradlew build · git status" />
                  {termBusy && <Loader2 size={13} className="animate-spin" />}
                </div>
              </div>
            ) : (
              <div className="flex min-h-0 flex-1">
                <div className="flex w-80 shrink-0 flex-col gap-2 border-r border-border p-2">
                  <textarea className="input min-h-[90px] flex-1 resize-none text-sm" placeholder="Describe the task, e.g. 'Add a BoatFly check to KYRO AntiCheat with a regression test, then build with Maven.'"
                    value={task} onChange={(e) => setTask(e.target.value)} />
                  {!projectId && <input className="input py-1 text-xs" value={scratch} onChange={(e) => setScratch(e.target.value)} title="Sub-folder of the KYRO workspace" placeholder="workspace sub-folder" />}
                  <div className="flex items-center gap-2">
                    <ModelSelect value={agentModel} onChange={setAgentModel} className="flex-1" />
                    {running ? <button className="btn-outline" onClick={cancel}><Square size={14} /> Stop</button>
                      : <button className="btn-primary" onClick={startAgent} disabled={!task.trim()}><Play size={14} /> Run</button>}
                  </div>
                  {run && !running && <button className="btn-outline justify-center" onClick={showDiffs}>Review changes ({run.changed_files?.length ?? 0})</button>}
                  {run && <button className="btn-ghost justify-center text-xs" onClick={() => download("/workspace/download", { run_id: run.id })}><Download size={13} /> Download result</button>}
                </div>
                <div ref={logRef} className="min-h-0 flex-1 overflow-y-auto p-2 text-xs">
                  {!run && <div className="text-muted">The agent plans, reads, implements, tests, debugs and verifies inside the workspace. Build/test success is only reported when a command actually exited with code 0.</div>}
                  {run?.plan?.length > 0 && (
                    <ol className="mb-2 list-decimal rounded-lg border border-border bg-panel p-2 pl-6">{run.plan.map((s: string, i: number) => <li key={i}>{s}</li>)}</ol>
                  )}
                  {events.map((e, i) => <AgentEvent key={i} e={e} />)}
                  {run?.pending && (
                    <div className="my-2 rounded-lg border border-amber-500/50 bg-amber-500/10 p-3">
                      <div className="mb-1 flex items-center gap-1 font-semibold text-amber-700 dark:text-amber-300"><ShieldAlert size={14} /> Approval required ({run.pending.kind})</div>
                      <pre className="whitespace-pre-wrap font-mono">{run.pending.command || run.pending.path}</pre>
                      <div className="mt-1 text-muted">{run.pending.reason}</div>
                      <div className="mt-2 flex gap-2">
                        <button className="btn-primary" onClick={() => approve(true)}><Check size={14} /> Approve</button>
                        <button className="btn-outline" onClick={() => approve(false)}><X size={14} /> Deny</button>
                      </div>
                    </div>
                  )}
                  {run?.summary && !running && <div className="mt-2 whitespace-pre-wrap rounded-lg border border-border bg-panel p-2 text-sm">{run.summary}</div>}
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function AgentEvent({ e }: { e: any }) {
  const [open, setOpen] = useState(false);
  switch (e.type) {
    case "phase": return <div className="my-1 font-semibold text-accent">▸ {e.phase}{e.note ? ` — ${e.note}` : ""}</div>;
    case "token": return null;
    case "message": return <div className="my-1 whitespace-pre-wrap text-fg/90">{e.content}</div>;
    case "tool_call": return (
      <div className="my-0.5">
        <button className="font-mono text-muted hover:text-fg" onClick={() => setOpen(!open)}>⚙ {e.name}({Object.entries(e.arguments || {}).map(([k, v]) => `${k}=${JSON.stringify(v).slice(0, 60)}`).join(", ")})</button>
        {open && <pre className="whitespace-pre-wrap rounded bg-panel2 p-1">{JSON.stringify(e.arguments, null, 2)}</pre>}
      </div>);
    case "tool_result": {
      const err = e.result?.error;
      return (
        <div className={clsx("ml-3", err ? "text-red-500" : "text-muted")}>
          <button onClick={() => setOpen(!open)}>{err ? `✗ ${String(err).slice(0, 160)}` : `✓ ${e.name}${e.result?.exit_code !== undefined ? ` (exit ${e.result.exit_code})` : ""}`}</button>
          {open && <pre className="whitespace-pre-wrap rounded bg-panel2 p-1">{JSON.stringify(e.result, null, 2)}</pre>}
        </div>);
    }
    case "command_output": return (
      <pre className={clsx("my-1 max-h-48 overflow-auto whitespace-pre-wrap rounded p-1.5 font-mono", e.exit_code === 0 ? "bg-panel2" : "bg-red-500/10")}>
        $ {e.command}{"\n"}{e.stdout}{e.stderr && `\n${e.stderr}`}{"\n"}[exit {e.exit_code}]
      </pre>);
    case "error": return <div className="my-1 rounded border border-red-500/40 bg-red-500/10 p-2 text-red-600">{e.message}</div>;
    case "finish": return <div className="my-1 font-semibold">Run {e.status}.</div>;
    case "verification": return <div className="my-1"><VerificationBadge v={e} /> <span className="text-muted">{e.detail}</span></div>;
    default: return null;
  }
}

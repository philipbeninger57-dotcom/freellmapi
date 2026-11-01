import { useState } from "react";
import { FolderKanban, Plus, Save, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { Project, useApp } from "../lib/store";

export default function ProjectsPage() {
  const { projects, refreshProjects, toast } = useApp();
  const [sel, setSel] = useState<string | null>(null);
  const [draft, setDraft] = useState<Partial<Project>>({});
  const current = projects.find((p) => p.id === sel);

  const pick = (p: Project) => { setSel(p.id); setDraft(p); };
  const create = async () => {
    const name = prompt("Project name:");
    if (!name) return;
    try { const p = await api.post("/projects", { name }); await refreshProjects(); pick(p); } catch (e: any) { toast(e.message); }
  };
  const save = async () => {
    if (!sel) return;
    try {
      await api.patch(`/projects/${sel}`, { name: draft.name, description: draft.description, instructions: draft.instructions, workspace_path: draft.workspace_path });
      await refreshProjects(); toast("Project saved", "success");
    } catch (e: any) { toast(e.message); }
  };
  const remove = async () => {
    if (!sel || !confirm("Delete this project and its project-scoped memories? Conversations are kept.")) return;
    await api.del(`/projects/${sel}`); setSel(null); refreshProjects();
  };

  return (
    <div className="flex h-full">
      <aside className="w-64 shrink-0 overflow-y-auto border-r border-border p-3">
        <button className="btn-primary mb-3 w-full justify-center" onClick={create}><Plus size={15} /> New project</button>
        {projects.map((p) => (
          <button key={p.id} onClick={() => pick(p)} className={`mb-1 block w-full rounded-lg px-3 py-2 text-left hover:bg-panel2 ${sel === p.id ? "bg-panel2" : ""}`}>
            <div className="flex items-center gap-2 font-medium"><FolderKanban size={14} className="text-accent" />{p.name}</div>
            <div className="text-xs text-muted">{p.memory_count} memories · {p.conversation_count} chats</div>
          </button>
        ))}
      </aside>
      <div className="min-w-0 flex-1 overflow-y-auto p-4 md:p-6">
        {!current ? <div className="text-sm text-muted">Select a project. Projects keep their own instructions, memories, conversations and workspace — separate from your general memory.</div> : (
          <div className="mx-auto max-w-3xl space-y-4">
            <input className="input text-lg font-medium" value={draft.name || ""} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            <label className="block space-y-1"><span className="label">Description</span>
              <input className="input" value={draft.description || ""} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
            </label>
            <label className="block space-y-1"><span className="label">Project instructions (added to every chat & agent run in this project)</span>
              <textarea className="input min-h-[180px] font-mono text-xs" value={draft.instructions || ""} onChange={(e) => setDraft({ ...draft, instructions: e.target.value })} />
            </label>
            <label className="block space-y-1"><span className="label">Workspace folder on this PC (optional, e.g. C:\dev\KYRO-AntiCheat)</span>
              <input className="input font-mono text-xs" placeholder="Leave empty to use the KYRO workspace" value={draft.workspace_path || ""} onChange={(e) => setDraft({ ...draft, workspace_path: e.target.value })} />
              <span className="text-xs text-muted">Setting a folder authorises the Code page and the agent to read and edit files inside it.</span>
            </label>
            <div className="flex gap-2">
              <button className="btn-primary" onClick={save}><Save size={15} /> Save</button>
              <span className="flex-1" />
              <button className="btn-danger" onClick={remove}><Trash2 size={15} /> Delete project</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

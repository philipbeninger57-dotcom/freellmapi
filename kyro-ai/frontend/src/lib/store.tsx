import { createContext, ReactNode, useCallback, useContext, useEffect, useState } from "react";
import { api } from "./api";

export type Project = {
  id: string; name: string; description: string; instructions: string; workspace_path: string;
  memory_count?: number; conversation_count?: number;
};
export type ConversationSummary = { id: string; title: string; project_id: string | null; mode: string; model: string; source: string; pinned: number; updated_at: number };
export type Toast = { id: number; kind: "error" | "info" | "success"; text: string };

type Ctx = {
  projects: Project[];
  conversations: ConversationSummary[];
  refreshProjects: () => Promise<void>;
  refreshConversations: () => Promise<void>;
  toasts: Toast[];
  toast: (text: string, kind?: Toast["kind"]) => void;
  dismiss: (id: number) => void;
  theme: "dark" | "light";
  toggleTheme: () => void;
  health: any;
  refreshHealth: () => Promise<void>;
  models: any;
  refreshModels: () => Promise<void>;
};

const AppCtx = createContext<Ctx>(null as any);
export const useApp = () => useContext(AppCtx);

let toastId = 0;

export function AppProvider({ children }: { children: ReactNode }) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [health, setHealth] = useState<any>(null);
  const [models, setModels] = useState<any>(null);
  const [theme, setTheme] = useState<"dark" | "light">(document.documentElement.classList.contains("dark") ? "dark" : "light");

  const toast = useCallback((text: string, kind: Toast["kind"] = "error") => {
    const id = ++toastId;
    setToasts((t) => [...t, { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 9000 : 4000);
  }, []);
  const dismiss = (id: number) => setToasts((t) => t.filter((x) => x.id !== id));

  const refreshProjects = useCallback(async () => {
    try { setProjects(await api.get("/projects")); } catch (e: any) { toast(`Could not load projects: ${e.message}`); }
  }, [toast]);
  const refreshConversations = useCallback(async () => {
    try { setConversations(await api.get("/conversations")); } catch (e: any) { toast(`Could not load conversations: ${e.message}`); }
  }, [toast]);
  const refreshHealth = useCallback(async () => {
    try { setHealth(await api.get("/health")); } catch (e: any) { setHealth({ ok: false, error: e.message }); }
  }, []);
  const refreshModels = useCallback(async () => {
    try { setModels(await api.get("/models")); } catch (e: any) { setModels({ ollama: [], cloud: [], errors: [e.message], roles: {} }); }
  }, []);

  useEffect(() => {
    refreshHealth(); refreshProjects(); refreshConversations(); refreshModels();
    const h = () => toast("Access token required or invalid. Enter it in Settings → Security.");
    window.addEventListener("kyro-unauthorized", h);
    return () => window.removeEventListener("kyro-unauthorized", h);
  }, [refreshHealth, refreshProjects, refreshConversations, refreshModels, toast]);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.classList.toggle("dark", next === "dark");
    try { localStorage.setItem("kyro-theme", next); } catch { /* ignore */ }
    setTheme(next);
  };

  return (
    <AppCtx.Provider value={{ projects, conversations, refreshProjects, refreshConversations, toasts, toast, dismiss, theme, toggleTheme, health, refreshHealth, models, refreshModels }}>
      {children}
    </AppCtx.Provider>
  );
}

export function timeAgo(ts: number): string {
  const s = Date.now() / 1000 - ts;
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  if (s < 86400 * 30) return `${Math.floor(s / 86400)}d ago`;
  return new Date(ts * 1000).toLocaleDateString();
}

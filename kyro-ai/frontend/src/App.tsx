import { NavLink, Route, Routes } from "react-router-dom";
import { Brain, Code2, FolderKanban, Image, MessageSquare, Moon, Settings, Sun, Upload, X } from "lucide-react";
import clsx from "clsx";
import { AppProvider, useApp } from "./lib/store";
import ChatPage from "./pages/ChatPage";
import CodePage from "./pages/CodePage";
import MemoryPage from "./pages/MemoryPage";
import ImportPage from "./pages/ImportPage";
import SettingsPage from "./pages/SettingsPage";
import ProjectsPage from "./pages/ProjectsPage";
import ImagesPage from "./pages/ImagesPage";

const NAV = [
  { to: "/", icon: MessageSquare, label: "Chat", end: true },
  { to: "/code", icon: Code2, label: "Code" },
  { to: "/projects", icon: FolderKanban, label: "Projects" },
  { to: "/memory", icon: Brain, label: "Memory" },
  { to: "/import", icon: Upload, label: "Import" },
  { to: "/images", icon: Image, label: "Images" },
  { to: "/settings", icon: Settings, label: "Settings" },
];

function Shell() {
  const { theme, toggleTheme, toasts, dismiss, health } = useApp();
  return (
    <div className="flex h-full flex-col md:flex-row">
      <nav className="flex shrink-0 items-center gap-1 border-b border-border bg-panel px-2 py-1.5 md:w-16 md:flex-col md:border-b-0 md:border-r md:px-0 md:py-3">
        <div className="mr-2 grid h-9 w-9 place-items-center rounded-xl bg-accent font-bold text-white md:mb-4 md:mr-0" title="KYRO AI">K</div>
        <div className="flex flex-1 gap-1 overflow-x-auto md:flex-col md:items-center">
          {NAV.map(({ to, icon: Icon, label, end }) => (
            <NavLink key={to} to={to} end={end} title={label}
              className={({ isActive }) => clsx("flex flex-col items-center rounded-lg px-2 py-1.5 text-[10px] text-muted transition-colors hover:bg-panel2 hover:text-fg md:w-12", isActive && "bg-panel2 text-fg")}>
              <Icon size={19} />
              <span className="mt-0.5 hidden md:block">{label}</span>
            </NavLink>
          ))}
        </div>
        <div className="flex items-center gap-1 md:flex-col">
          <span title={!health ? "Checking…" : health.ollama?.ok ? `Ollama ${health.ollama.version}` : health.ollama?.error || health.error || "Backend not reachable"}
            className={clsx("h-2.5 w-2.5 rounded-full", !health ? "bg-muted/40" : health.ollama?.ok ? "bg-emerald-500" : "bg-red-500")} />
          <button onClick={toggleTheme} className="btn-ghost p-2" title="Toggle theme">{theme === "dark" ? <Sun size={17} /> : <Moon size={17} />}</button>
        </div>
      </nav>
      <main className="min-h-0 min-w-0 flex-1">
        <Routes>
          <Route path="/" element={<ChatPage />} />
          <Route path="/c/:id" element={<ChatPage />} />
          <Route path="/code" element={<CodePage />} />
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/import" element={<ImportPage />} />
          <Route path="/images" element={<ImagesPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Routes>
      </main>
      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[min(420px,calc(100vw-2rem))] flex-col gap-2">
        {toasts.map((t) => (
          <div key={t.id} role="alert"
            className={clsx("pointer-events-auto flex items-start gap-2 rounded-lg border px-3 py-2 text-sm shadow-lg",
              t.kind === "error" ? "border-red-500/40 bg-red-50 text-red-900 dark:bg-red-950 dark:text-red-100" :
              t.kind === "success" ? "border-emerald-500/40 bg-emerald-50 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-100" : "border-border bg-panel")}>
            <span className="flex-1 whitespace-pre-wrap break-words">{t.text}</span>
            <button onClick={() => dismiss(t.id)} className="opacity-60 hover:opacity-100"><X size={14} /></button>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function App() {
  return (
    <AppProvider>
      <Shell />
    </AppProvider>
  );
}

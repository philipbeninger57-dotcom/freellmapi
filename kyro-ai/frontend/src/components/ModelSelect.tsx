import { useApp } from "../lib/store";

/** Model picker: "auto" (task-based routing), installed Ollama models, optional cloud models. */
export default function ModelSelect({ value, onChange, className = "" }: { value: string; onChange: (v: string) => void; className?: string }) {
  const { models } = useApp();
  const local: any[] = (models?.ollama || []).filter((m: any) => !m.capabilities?.includes("embedding"));
  const cloud: any[] = models?.cloud || [];
  const known = value === "auto" || local.some((m) => m.name === value) || cloud.some((m) => `cloud:${m.name}` === value);
  return (
    <select className={`input w-auto py-1 text-xs ${className}`} value={value} onChange={(e) => onChange(e.target.value)} title="Model">
      <option value="auto">Auto (by task)</option>
      {!known && <option value={value}>{value} (not installed)</option>}
      {local.length > 0 && (
        <optgroup label="Local (Ollama)">
          {local.map((m) => (
            <option key={m.name} value={m.name}>{m.name}{m.parameter_size ? ` · ${m.parameter_size}` : ""}{m.capabilities?.includes("vision") ? " · vision" : ""}</option>
          ))}
        </optgroup>
      )}
      {cloud.length > 0 && (
        <optgroup label="Cloud (optional)">
          {cloud.map((m) => <option key={m.name} value={`cloud:${m.name}`}>{m.name}</option>)}
        </optgroup>
      )}
    </select>
  );
}

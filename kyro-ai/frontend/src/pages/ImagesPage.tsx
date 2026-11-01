import { useEffect, useRef, useState } from "react";
import { Download, ImagePlus, Loader2, Wand2 } from "lucide-react";
import { api } from "../lib/api";
import { useApp } from "../lib/store";

export default function ImagesPage() {
  const { toast } = useApp();
  const [status, setStatus] = useState<any>(null);
  const [prompt, setPrompt] = useState("");
  const [negative, setNegative] = useState("blurry, low quality, watermark");
  const [size, setSize] = useState("1024x1024");
  const [ckpt, setCkpt] = useState("");
  const [init, setInit] = useState<any>(null);
  const [denoise, setDenoise] = useState(0.6);
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<any[]>([]);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => { api.get("/images/status").then(setStatus).catch((e) => setStatus({ available: false, reason: e.message })); }, []);

  const generate = async () => {
    const [w, h] = size.split("x").map(Number);
    setBusy(true);
    try {
      const r = await api.post("/images/generate", { prompt, negative, width: w, height: h, checkpoint: ckpt || undefined, init_attachment_id: init?.id, denoise });
      setResults((x) => [...r, ...x]);
    } catch (e: any) { toast(e.message); } finally { setBusy(false); }
  };
  const uploadInit = async (f: File) => {
    const fd = new FormData(); fd.append("files", f);
    const [a] = await api.upload("/files", fd);
    setInit(a);
  };

  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl space-y-4 p-4 md:p-6">
        <h1 className="font-serif text-2xl">Images</h1>
        {status && !status.available && (
          <div className="card border-amber-500/50 p-4 text-sm">
            <div className="font-medium">Local image generation is optional and not active</div>
            <div className="mt-1 text-muted">{status.reason}. Install <a className="text-accent underline" href="https://github.com/comfyanonymous/ComfyUI" target="_blank" rel="noreferrer">ComfyUI</a>, put an SDXL or SD 1.5 checkpoint in <code>models/checkpoints</code>, start it, and set its URL (default <code>http://127.0.0.1:8188</code>) in Settings. Image <em>analysis</em> works in Chat with a vision model.</div>
          </div>
        )}
        <div className="card space-y-3 p-4">
          <textarea className="input min-h-[90px]" placeholder="A cozy medieval Minecraft-style village at sunset, isometric, detailed" value={prompt} onChange={(e) => setPrompt(e.target.value)} />
          <input className="input" placeholder="Negative prompt" value={negative} onChange={(e) => setNegative(e.target.value)} />
          <div className="flex flex-wrap items-center gap-2">
            <select className="input w-auto" value={size} onChange={(e) => setSize(e.target.value)}>{["512x512", "768x768", "1024x1024", "1216x832", "832x1216"].map((s) => <option key={s}>{s}</option>)}</select>
            {status?.checkpoints && <select className="input w-auto" value={ckpt} onChange={(e) => setCkpt(e.target.value)}><option value="">Default checkpoint</option>{status.checkpoints.map((c: string) => <option key={c}>{c}</option>)}</select>}
            <input ref={fileRef} type="file" accept="image/*" hidden onChange={(e) => e.target.files?.[0] && uploadInit(e.target.files[0])} />
            <button className="btn-outline" onClick={() => fileRef.current?.click()}><ImagePlus size={15} /> {init ? init.filename : "Edit an image (img2img)"}</button>
            {init && <label className="flex items-center gap-1 text-xs">strength <input type="range" min={0.1} max={1} step={0.05} value={denoise} onChange={(e) => setDenoise(+e.target.value)} /> {denoise}</label>}
            <span className="flex-1" />
            <button className="btn-primary" disabled={!prompt.trim() || busy || !status?.available} onClick={generate}>{busy ? <Loader2 size={15} className="animate-spin" /> : <Wand2 size={15} />} Generate</button>
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          {results.map((r) => (
            <div key={r.id} className="card overflow-hidden">
              <img src={api.url(`/files/${r.id}/raw`)} alt={r.filename} className="w-full" />
              <a className="btn-ghost m-1" href={api.url(`/files/${r.id}/raw`)} download={r.filename}><Download size={14} /> Download</a>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

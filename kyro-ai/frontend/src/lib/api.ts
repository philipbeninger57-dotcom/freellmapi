import { SSEParser, SSEEvent } from "./sse";

export function getToken(): string {
  try {
    return localStorage.getItem("kyro-token") || "";
  } catch {
    return "";
  }
}
export function setToken(t: string) {
  try {
    localStorage.setItem("kyro-token", t);
  } catch {
    /* ignore */
  }
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function headers(extra: Record<string, string> = {}): Record<string, string> {
  const t = getToken();
  return { ...(t ? { "x-kyro-token": t } : {}), ...extra };
}

async function handle(r: Response) {
  if (!r.ok) {
    let msg = `${r.status} ${r.statusText}`;
    try {
      const j = await r.json();
      msg = typeof j.detail === "string" ? j.detail : JSON.stringify(j.detail ?? j);
    } catch {
      /* not json */
    }
    if (r.status === 401) window.dispatchEvent(new CustomEvent("kyro-unauthorized"));
    throw new ApiError(r.status, msg);
  }
  const ct = r.headers.get("content-type") || "";
  return ct.includes("json") ? r.json() : r.text();
}

export const api = {
  get: <T = any>(path: string, params?: Record<string, any>): Promise<T> => {
    const q = params
      ? "?" + new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== "") as any)
      : "";
    return fetch(`/api${path}${q}`, { headers: headers() }).then(handle);
  },
  post: <T = any>(path: string, body?: any): Promise<T> =>
    fetch(`/api${path}`, { method: "POST", headers: headers({ "Content-Type": "application/json" }), body: JSON.stringify(body ?? {}) }).then(handle),
  patch: <T = any>(path: string, body: any): Promise<T> =>
    fetch(`/api${path}`, { method: "PATCH", headers: headers({ "Content-Type": "application/json" }), body: JSON.stringify(body) }).then(handle),
  put: <T = any>(path: string, body: any): Promise<T> =>
    fetch(`/api${path}`, { method: "PUT", headers: headers({ "Content-Type": "application/json" }), body: JSON.stringify(body) }).then(handle),
  del: <T = any>(path: string, params?: Record<string, any>): Promise<T> => {
    const q = params ? "?" + new URLSearchParams(Object.entries(params).filter(([, v]) => v != null) as any) : "";
    return fetch(`/api${path}${q}`, { method: "DELETE", headers: headers() }).then(handle);
  },
  upload: <T = any>(path: string, form: FormData): Promise<T> =>
    fetch(`/api${path}`, { method: "POST", headers: headers(), body: form }).then(handle),
  /** URL usable in <a href> / <img src> (token passed as query when auth is on). */
  url: (path: string, params: Record<string, string> = {}) => {
    const t = getToken();
    const p = new URLSearchParams({ ...params, ...(t ? { token: t } : {}) }).toString();
    return `/api${path}${p ? "?" + p : ""}`;
  },
};

/** POST (or GET) a request and stream SSE events. Returns an abort function. */
export function stream(
  path: string,
  body: any | null,
  onEvent: (e: SSEEvent) => void,
  onError: (e: Error) => void,
  onClose?: () => void,
): () => void {
  const ctrl = new AbortController();
  (async () => {
    try {
      const r = await fetch(`/api${path}`, {
        method: body === null ? "GET" : "POST",
        headers: headers(body === null ? {} : { "Content-Type": "application/json" }),
        body: body === null ? undefined : JSON.stringify(body),
        signal: ctrl.signal,
      });
      if (!r.ok || !r.body) {
        await handle(r);
        return;
      }
      const reader = r.body.getReader();
      const dec = new TextDecoder();
      const parser = new SSEParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const ev of parser.feed(dec.decode(value, { stream: true }))) onEvent(ev);
      }
      onClose?.();
    } catch (e: any) {
      if (e?.name === "AbortError") {
        onClose?.();
        return;
      }
      onError(e instanceof Error ? e : new Error(String(e)));
    }
  })();
  return () => ctrl.abort();
}

export function download(path: string, params: Record<string, string> = {}) {
  const a = document.createElement("a");
  a.href = api.url(path, params);
  a.click();
}

export function saveBlob(content: string, filename: string, type = "text/plain") {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

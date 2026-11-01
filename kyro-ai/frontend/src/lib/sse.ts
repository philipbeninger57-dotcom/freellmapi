export type SSEEvent = { event: string; data: any };

/** Incremental Server-Sent-Events parser. Feed text chunks, get complete events back. */
export class SSEParser {
  private buf = "";

  feed(chunk: string): SSEEvent[] {
    this.buf += chunk.replace(/\r\n/g, "\n");
    const out: SSEEvent[] = [];
    let idx: number;
    while ((idx = this.buf.indexOf("\n\n")) !== -1) {
      const block = this.buf.slice(0, idx);
      this.buf = this.buf.slice(idx + 2);
      let event = "message";
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith(":")) continue;
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
      }
      if (!data.length) continue;
      const raw = data.join("\n");
      let parsed: any = raw;
      try {
        parsed = JSON.parse(raw);
      } catch {
        /* keep raw text */
      }
      out.push({ event, data: parsed });
    }
    return out;
  }
}

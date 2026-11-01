# Choosing local models (32 GB RAM PC)

KYRO sends each task to a model by its role. You can change the model for any role under **Settings → Model per
task**. The **Pull** button downloads a missing model through Ollama.

| Role | `standard` (32 GB RAM) | `light` (16 GB RAM) | Notes |
|---|---|---|---|
| chat | `qwen3:14b` (~9 GB) | `qwen3:8b` | Strong general model with multilingual (German) support |
| reasoning | `deepseek-r1:14b` (~9 GB) | `qwen3:8b` | Streams its reasoning; slower |
| coding / agent | `qwen2.5-coder:14b` (~9 GB) | `qwen2.5-coder:7b` | Supports tool calling, which the agent requires |
| vision | `qwen2.5vl:7b` (~6 GB) | `qwen2.5vl:7b` | Screenshots, images, OCR-like reading. Alternatives: `llama3.2-vision:11b`, `gemma3:12b` |
| embedding | `nomic-embed-text` (274 MB) | `nomic-embed-text` | Semantic memory. `bge-m3` is better for mixed German/English text |
| summarize | `qwen3:8b` | `qwen3:4b` | Rolling summaries for long chats |
| fast | `qwen3:4b` | `qwen3:4b` | Titles and optional memory extraction |

How the hardware is used:

- Ollama keeps the most recently used model in memory and swaps models when the role changes. With 32 GB RAM, one
  14B model plus the embedding model fit comfortably.
- With an NVIDIA GPU of 12 GB or more, 14B models run mostly on the GPU and reach 20–40 tokens/s. On CPU only,
  expect about 3–8 tokens/s.
- A larger context (`num_ctx`) needs more RAM. 16k is a good default; 32k works for 8B models.
- If you change the embedding model, press **Re-embed** on the Memory page so all memories use the same vectors.

KYRO uses only open-weight models. It does not include or imitate proprietary Claude or ChatGPT weights.

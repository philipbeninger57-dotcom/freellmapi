"""Optional local image generation/editing through a running ComfyUI server.

KYRO submits a standard SD/SDXL text-to-image (or img2img) graph to ComfyUI's /prompt API,
polls /history and downloads the result. Nothing is generated if ComfyUI is not configured.
"""
from __future__ import annotations

import asyncio
import random
import uuid
from pathlib import Path

import httpx


def txt2img_workflow(prompt: str, negative: str, ckpt: str, width: int, height: int, steps: int, seed: int,
                     init_image: str | None = None, denoise: float = 1.0) -> dict:
    wf = {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": ckpt}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["4", 1]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["4", 1]}},
        "5": {"class_type": "EmptyLatentImage", "inputs": {"width": width, "height": height, "batch_size": 1}},
        "3": {"class_type": "KSampler", "inputs": {"seed": seed, "steps": steps, "cfg": 7, "sampler_name": "euler",
                                                    "scheduler": "normal", "denoise": denoise, "model": ["4", 0],
                                                    "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "kyro", "images": ["8", 0]}},
    }
    if init_image:  # img2img editing
        wf["10"] = {"class_type": "LoadImage", "inputs": {"image": init_image}}
        wf["11"] = {"class_type": "VAEEncode", "inputs": {"pixels": ["10", 0], "vae": ["4", 2]}}
        wf["3"]["inputs"]["latent_image"] = ["11", 0]
        del wf["5"]
    return wf


class ComfyUI:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")

    async def checkpoints(self) -> list[str]:
        async with httpx.AsyncClient(timeout=10, trust_env=False) as c:
            r = await c.get(f"{self.base}/object_info/CheckpointLoaderSimple")
            r.raise_for_status()
            return r.json()["CheckpointLoaderSimple"]["input"]["required"]["ckpt_name"][0]

    async def generate(self, prompt: str, out_dir: Path, *, negative: str = "blurry, low quality", ckpt: str | None = None,
                       width: int = 1024, height: int = 1024, steps: int = 25, init_image_path: Path | None = None,
                       denoise: float = 0.6, timeout: float = 600) -> list[Path]:
        async with httpx.AsyncClient(timeout=60, trust_env=False) as c:
            ckpt = ckpt or (await self.checkpoints())[0]
            uploaded = None
            if init_image_path:
                r = await c.post(f"{self.base}/upload/image", files={"image": (init_image_path.name, init_image_path.read_bytes())})
                r.raise_for_status()
                uploaded = r.json()["name"]
            wf = txt2img_workflow(prompt, negative, ckpt, width, height, steps, random.randint(0, 2**31),
                                  uploaded, denoise if uploaded else 1.0)
            r = await c.post(f"{self.base}/prompt", json={"prompt": wf, "client_id": uuid.uuid4().hex})
            r.raise_for_status()
            pid = r.json()["prompt_id"]
            waited = 0.0
            while waited < timeout:
                h = (await c.get(f"{self.base}/history/{pid}")).json()
                if pid in h:
                    out = []
                    for node in h[pid]["outputs"].values():
                        for img in node.get("images", []):
                            data = (await c.get(f"{self.base}/view", params=img)).content
                            p = out_dir / f"{uuid.uuid4().hex}_{img['filename']}"
                            p.write_bytes(data)
                            out.append(p)
                    return out
                await asyncio.sleep(1.5)
                waited += 1.5
            raise TimeoutError("ComfyUI did not finish in time")

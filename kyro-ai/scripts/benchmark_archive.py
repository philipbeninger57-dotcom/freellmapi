"""Synthetic archive scale check; no models, network or personal data required.

Run from kyro-ai: backend/.venv/Scripts/python scripts/benchmark_archive.py --messages 1000000
Results describe this machine and synthetic short messages, not answer quality.
"""
import argparse
import asyncio
import json
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from kyro.archive import ArchiveStore
from kyro.bulk_import import BulkImports
from kyro.config import Settings
from kyro.db import Database


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--messages", type=int, default=10000)
    args = parser.parse_args()
    if args.messages < 1:
        parser.error("messages must be positive")
    with tempfile.TemporaryDirectory(prefix="kyro-archive-benchmark-") as directory:
        root = Path(directory)
        dataset = root / "synthetic.jsonl"
        with dataset.open("w", encoding="utf-8") as out:
            for i in range(args.messages):
                out.write(json.dumps({
                    "id": str(i), "source": "synthetic", "conversation_id": f"thread-{i // 20}",
                    "role": "user" if i % 2 == 0 else "assistant", "timestamp": 1700000000 + i,
                    "content": f"Synthetic Paper Folia scheduler discussion record{i}. Java 21 Maven build context.",
                }) + "\n")
        settings = Settings(root / "data")
        settings.update({"archive": {"semantic_rerank": False}})
        db = Database(settings.db_path)
        milestone = 0
        def progress(job):
            nonlocal milestone
            if job["processed"] >= milestone + 50000:
                milestone = job["processed"]
                print(f"{milestone:,} messages committed", flush=True)
        bulk = BulkImports(db, settings, progress)
        with dataset.open("rb") as source:
            job = bulk.stage(source, dataset.name, None)
        started = time.perf_counter()
        bulk.run(job["id"])
        elapsed = time.perf_counter() - started
        result = bulk.get(job["id"])
        if result["status"] != "done" or result["inserted"] != args.messages:
            raise RuntimeError(result)
        store = ArchiveStore(db, None, settings)
        query_started = time.perf_counter()
        hits = asyncio.run(store.search(f"record{args.messages - 1}"))
        query_ms = (time.perf_counter() - query_started) * 1000
        if not hits or f"record{args.messages - 1}." not in hits[0]["content"]:
            raise RuntimeError("Archive retrieval failed")
        common_started = time.perf_counter()
        common_hits = asyncio.run(store.search("Folia"))
        common_ms = (time.perf_counter() - common_started) * 1000
        if not common_hits:
            raise RuntimeError("Common-term archive retrieval failed")
        summary = {
            "messages": result["inserted"], "import_seconds": round(elapsed, 3),
            "messages_per_second": round(args.messages / elapsed, 1),
            "single_keyword_query_ms": round(query_ms, 3),
            "common_keyword_query_ms": round(common_ms, 3),
            "stored_bytes": sum(p.stat().st_size for p in (root / "data").rglob("*") if p.is_file()),
            "model_calls": 0,
        }
        try:
            import resource
            summary["peak_process_rss_mib"] = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / (1024**2 if sys.platform == "darwin" else 1024), 1)
        except ImportError:
            pass
        print(json.dumps(summary, indent=2), flush=True)
        db.close_thread()


if __name__ == "__main__":
    main()

"""Start KYRO AI: python -m kyro [--host 127.0.0.1] [--port 8765] [--data-dir PATH]"""
import argparse
import os
import webbrowser

import uvicorn

from .config import Settings
from .main import create_app


def main() -> None:
    ap = argparse.ArgumentParser(prog="kyro")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--data-dir")
    ap.add_argument("--open", action="store_true", help="open the browser after start")
    a = ap.parse_args()
    if a.data_dir:
        os.environ["KYRO_DATA_DIR"] = a.data_dir
    s = Settings()
    host, port = a.host or s["host"], a.port or s["port"]
    if host not in ("127.0.0.1", "localhost", "::1") and not s["auth"]["enabled"]:
        print("[kyro] WARNING: listening on a non-local address without an access token. Enable auth in Settings.")
    print(f"[kyro] data dir: {s.data_dir}")
    print(f"[kyro] open http://{'127.0.0.1' if host in ('0.0.0.0', '::') else host}:{port}")
    if a.open:
        webbrowser.open(f"http://127.0.0.1:{port}")
    uvicorn.run(create_app(s), host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()

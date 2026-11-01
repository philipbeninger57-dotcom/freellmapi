"""Web search with citations. Uses a self-hosted SearXNG instance if configured, otherwise the
DuckDuckGo HTML endpoint (no API key). Pages are fetched and reduced to readable text."""
from __future__ import annotations

import html
import ipaddress
import re
import socket
from urllib.parse import parse_qs, unquote, urlparse

import httpx

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) KYRO-AI/1.0"


def _strip_html(s: str) -> str:
    s = re.sub(r"(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>", " ", s)
    s = re.sub(r"(?s)<br\s*/?>|</p>|</div>|</li>|</h\d>", "\n", s)
    s = re.sub(r"(?s)<[^>]+>", " ", s)
    s = html.unescape(s)
    s = re.sub(r"[ \t\r\f\v]+", " ", s)
    return re.sub(r"\n\s*\n+", "\n\n", s).strip()


def is_public_url(url: str) -> bool:
    """Block fetches to localhost/private networks (SSRF protection)."""
    try:
        u = urlparse(url)
        if u.scheme not in ("http", "https") or not u.hostname:
            return False
        for info in socket.getaddrinfo(u.hostname, None):
            ip = ipaddress.ip_address(info[4][0])
            if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
                return False
        return True
    except Exception:  # noqa: BLE001
        return False


def parse_ddg_html(body: str, limit: int) -> list[dict]:
    results = []
    for m in re.finditer(r'(?s)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]+class="result__a"|$)', body):
        href, title, rest = m.groups()
        if "uddg=" in href:
            href = unquote(parse_qs(urlparse(html.unescape(href)).query).get("uddg", [href])[0])
        sm = re.search(r'(?s)class="result__snippet"[^>]*>(.*?)</a>', rest)
        if "duckduckgo.com/y.js" in href:  # ads
            continue
        results.append({"title": _strip_html(title), "url": href, "snippet": _strip_html(sm.group(1)) if sm else ""})
        if len(results) >= limit:
            break
    return results


async def search(query: str, *, searxng_url: str = "", limit: int = 6) -> list[dict]:
    async with httpx.AsyncClient(timeout=15, headers={"User-Agent": UA}, follow_redirects=True) as c:
        if searxng_url:
            r = await c.get(searxng_url.rstrip("/") + "/search", params={"q": query, "format": "json"})
            r.raise_for_status()
            return [{"title": x.get("title", ""), "url": x.get("url", ""), "snippet": x.get("content", "")}
                    for x in r.json().get("results", [])[:limit]]
        r = await c.post("https://html.duckduckgo.com/html/", data={"q": query})
        r.raise_for_status()
        return parse_ddg_html(r.text, limit)


async def fetch_text(url: str, max_chars: int = 12000) -> str:
    if not is_public_url(url):
        raise ValueError("Refusing to fetch non-public URL")
    async with httpx.AsyncClient(timeout=15, headers={"User-Agent": UA}, follow_redirects=True) as c:
        r = await c.get(url)
        r.raise_for_status()
        ct = r.headers.get("content-type", "")
        if "html" in ct:
            return _strip_html(r.text)[:max_chars]
        if ct.startswith("text/") or "json" in ct:
            return r.text[:max_chars]
        return f"[non-text content: {ct}]"


async def research(query: str, *, searxng_url: str = "", limit: int = 5, fetch: int = 3) -> list[dict]:
    """Search and fetch the top pages. Each result gets a 1-based citation number."""
    results = await search(query, searxng_url=searxng_url, limit=limit)
    for i, r in enumerate(results):
        r["n"] = i + 1
        if i < fetch:
            try:
                r["content"] = await fetch_text(r["url"], 6000)
            except Exception as e:  # noqa: BLE001
                r["content"] = ""
                r["fetch_error"] = str(e)[:200]
    return results

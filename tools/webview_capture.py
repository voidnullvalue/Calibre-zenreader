#!/usr/bin/env python3
"""Capture the live WebView of a running CalibReader debug build over CDP.

The debug build enables WebView remote debugging, so this works even when the
tablet's screen is locked and `adb screencap` only shows the keyguard.

Usage:
    adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
    python3 tools/webview_capture.py --out artifacts/screenshots/webview.png
"""
import argparse
import asyncio
import base64
import json
import pathlib
import urllib.request

import websockets


def pick_target(port):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=5) as response:
        targets = json.load(response)
    pages = [t for t in targets if t.get("type") == "page"]
    if not pages:
        raise SystemExit("no page target in devtools list")
    return pages[0]


async def call(ws, ident, method, params=None):
    await ws.send(json.dumps({"id": ident, "method": method, "params": params or {}}))
    while True:
        message = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
        if message.get("id") == ident:
            return message


async def capture(port, out, expression):
    target = pick_target(port)
    summary = {
        "url": target.get("url"),
        "title": target.get("title"),
        "description": target.get("description"),
    }
    async with websockets.connect(target["webSocketDebuggerUrl"], max_size=64 * 1024 * 1024) as ws:
        await call(ws, 1, "Page.enable")
        await call(ws, 2, "Runtime.enable")
        evaluated = await call(ws, 3, "Runtime.evaluate",
                               {"expression": expression, "returnByValue": True})
        try:
            summary["evaluated"] = evaluated["result"]["result"].get("value")
        except (KeyError, TypeError):
            summary["evaluated"] = None
        shot = await call(ws, 4, "Page.captureScreenshot",
                          {"format": "png", "fromSurface": True})
        data = shot.get("result", {}).get("data")
        if data:
            pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
            pathlib.Path(out).write_bytes(base64.b64decode(data))
            summary["screenshot"] = out
            summary["screenshot_bytes"] = pathlib.Path(out).stat().st_size
        else:
            summary["screenshot_error"] = shot
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9222)
    parser.add_argument("--out", default="artifacts/screenshots/webview.png")
    parser.add_argument("--eval", default=(
        "JSON.stringify({title:document.title,url:location.href,"
        "ready:document.readyState,nodes:document.querySelectorAll('*').length,"
        "text:(document.body.innerText||'').slice(0,300),"
        "nav:performance.getEntriesByType('navigation').map(n=>n.name)})"
    ))
    args = parser.parse_args()
    asyncio.run(capture(args.port, args.out, args.eval))


if __name__ == "__main__":
    main()

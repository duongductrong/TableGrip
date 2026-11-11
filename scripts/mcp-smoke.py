#!/usr/bin/env python3
"""Checks that a TableGrip build serves MCP over stdio, the way an AI app starts it.

    scripts/mcp-smoke.py /Applications/TableGrip.app/Contents/MacOS/TableGrip
    scripts/mcp-smoke.py scripts/tablegrip

Starts `<launcher> mcp` with a throwaway profile, sends initialize and tools/list, and fails unless every line on
stdout is a JSON-RPC message, the answers arrive within a minute, and the server exits cleanly when stdin closes.
The release workflow runs it on each packaged app, which is what proves stdio reaches the Windows launcher.
"""
import json
import queue
import subprocess
import sys
import tempfile
import threading
import time


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    home = tempfile.mkdtemp(prefix="tablegrip-smoke-")
    with open(f"{home}/settings.json", "w") as settings:
        settings.write('{ "mcpEnabled": true }\n')
    process = subprocess.Popen(
        [sys.argv[1], "mcp", "--home", home],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    lines: "queue.Queue[str | None]" = queue.Queue()

    def read() -> None:
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=read, daemon=True).start()

    def send(message: dict) -> None:
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def answer(request_id: int, deadline: float) -> dict:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise SystemExit(f"No answer to request {request_id} in time.\nstderr:\n{process.stderr.read() if process.poll() is not None else ''}")
            line = lines.get(timeout=remaining)
            if line is None:
                raise SystemExit(f"The server closed stdout early (exit {process.wait()}).\nstderr:\n{process.stderr.read()}")
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                raise SystemExit(f"Not a JSON-RPC message on stdout: {line!r}")
            if message.get("id") == request_id:
                if "error" in message:
                    raise SystemExit(f"Request {request_id} failed: {message['error']}")
                return message["result"]

    deadline = time.monotonic() + 60
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "smoke", "version": "1"}}})
    initialized = answer(1, deadline)
    assert initialized["serverInfo"]["name"] == "tablegrip", initialized
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    tools = [tool["name"] for tool in answer(2, deadline)["tools"]]
    assert "query" in tools and "list_connections" in tools, tools
    send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "list_connections", "arguments": {}}})
    listed = answer(3, deadline)
    assert listed["isError"] is False, listed

    process.stdin.close()
    try:
        code = process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        raise SystemExit("The server didn't exit after stdin closed.")
    if code != 0:
        raise SystemExit(f"The server exited with {code}.\nstderr:\n{process.stderr.read()}")
    print(f"MCP OK: {initialized['serverInfo']['version']}, protocol {initialized['protocolVersion']}, {len(tools)} tools")
    return 0


if __name__ == "__main__":
    sys.exit(main())

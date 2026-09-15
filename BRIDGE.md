# Termux Bridge — Canonical Handoff

This file records the durable technical context for the Android/Termux execution bridge used with ChatGPT. Future conversations should read this before troubleshooting or recreating the bridge.

## Purpose

Target flow:

`ChatGPT writes/edits code -> files reach Android -> Termux executes/builds/tests -> device installs/runs output`

The bridge is the **execution hand**. A separate visibility/control layer such as Remote Desktop Commander is the **eyes**.

## Last known Android environment

- Termux operational.
- Python installed.
- Rust installed.
- OpenSSH installed.
- Local SSH server previously tested on port `8022`.
- `cloudflared` installed.
- Main bridge workspace: `~/AI-Bridge`.
- Main bridge file: `~/AI-Bridge/bridge.py`.
- FastMCP server name: `Termux Bridge`.
- Last known bind address: `127.0.0.1`.
- Last known port: `8765`.
- FastMCP configuration included `stateless_http=True` and `json_response=True`.
- Public MCP endpoint pattern when using a Cloudflare Quick Tunnel: `https://<random>.trycloudflare.com/mcp`.

## Important implementation history

The early design mentioned FastAPI/Uvicorn, but the actual bridge implementation later used `mcp.server.fastmcp.FastMCP`. Do not revert to the older FastAPI assumption without inspecting the current `bridge.py` first.

An earlier Uvicorn error was:

`Attribute "app" not found in module "bridge"`

That belonged to the older launch approach and should not be used as evidence that the current FastMCP design is broken.

## Known Cloudflare issue

Cloudflare Quick Tunnel URLs rotate. A previously observed failure was:

`421 Misdirected Request`

with a FastMCP transport-security warning for an invalid Host header. The cause was that `transport_security.allowed_hosts` still contained an older `*.trycloudflare.com` hostname after the tunnel URL rotated.

When using Quick Tunnel:

1. Read the **current** tunnel hostname from the running `cloudflared` session.
2. Inspect `~/AI-Bridge/bridge.py` and make sure the current hostname is allowed by FastMCP transport security.
3. The ChatGPT MCP/action endpoint must use the same current hostname with `/mcp` appended.
4. Verify externally before claiming success.

Do not reuse an old Quick Tunnel hostname from notes or memory.

## Permanent-path idea

A more stable future option is a Cloudflare Named Tunnel with a stable hostname/domain. The user prefers free solutions and does not want workflows that depend on SMS MFA. Do not force this migration unless requested; Quick Tunnel may still be used for temporary tests.

## Operational rules

- Do **not** reinstall Termux, Python, Rust, OpenSSH, cloudflared, or rebuild the bridge from scratch unless current-state inspection proves it is necessary.
- Continue from the existing environment.
- Never claim the bridge is connected merely because code/configuration exists. Verify the current server/process/tunnel and actual reachability.
- If the device was rebooted, assume only that processes may need restarting; do not assume files/configuration were lost.
- If the tunnel rotates, update only what is necessary: current allowed host and MCP endpoint.
- Keep diagnostics short and targeted. Read actual logs before changing architecture.
- If a command can be executed through an available connector/tool, do not ask the user to type it manually.

## Bridge vs build pipeline

For Android application projects such as RUN, the preferred pattern is:

- GitHub / GitHub Actions: source control, tests, lint, compilation, APK artifacts.
- Termux / Bridge: device-side commands, installation, runtime checks, log collection, file transfer when needed.
- Remote Desktop Commander / Eyes: confirm device visibility/control and perform UI/device interactions when available.

The Bridge is not itself the Android application project and should not be confused with RUN or Binaa.

## Verification checklist

Before saying “the bridge works,” verify as many as applicable:

- `bridge.py` exists and matches the intended FastMCP implementation.
- The FastMCP server process is running on `127.0.0.1:8765`.
- `cloudflared` is running if public access is required.
- The current Cloudflare hostname matches FastMCP `allowed_hosts`.
- `/mcp` is reachable through the current public hostname.
- The connected ChatGPT MCP/action configuration points to that same endpoint.
- If device execution is requested, confirm the command actually ran and report its output/error.

Update this file whenever the bridge architecture, permanent endpoint, startup method, or workspace path changes.

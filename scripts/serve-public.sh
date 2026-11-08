#!/bin/sh
# Run the Finban server and (optionally) put it on a public HTTPS address so phones on ANY network can reach it.
#
#   scripts/serve-public.sh              # ngrok on the permanent domain (default galileo-tiliaceous-causally.ngrok-free.dev; NGROK_DOMAIN overrides)
#   scripts/serve-public.sh funnel       # Tailscale Funnel (stable https://<machine>.<tailnet>.ts.net; phones need no Tailscale)
#   scripts/serve-public.sh ngrok        # ngrok (use a free static domain: NGROK_DOMAIN=your-name.ngrok-free.app)
#   scripts/serve-public.sh cloudflared  # Cloudflare quick tunnel (new URL on every run)
#   scripts/serve-public.sh local        # no tunnel: server reachable on this laptop's LAN (same Wi-Fi) or via adb reverse
#   scripts/serve-public.sh stop         # stop the server and any ngrok/cloudflared tunnel started for it
#   PORT=8001 scripts/serve-public.sh ... # different port (default 8000)
#
# Reads ../.env (real variable names: see docs/DEMO-SETUP.md; NGROK_DOMAIN can live there too). Ctrl-C stops the server and the tunnel.
# The API is public, and so are the fictional scam pages at /demo (DEMO_PAGES=0 turns them off; see docs/DEMO-PAGES.md).
# Every API endpoint except /health needs a Firebase sign-in token.
PORT="${PORT:-8000}"
MODE="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/server" || exit 1
[ -f "$ROOT/.env" ] && { set -a; . "$ROOT/.env"; set +a; }

# Defaults for this repo: the service-account JSON next to the code (gitignored) and the Firebase project.
[ -z "$FIREBASE_CREDENTIALS" ] && [ -f "$ROOT/finban-private.json" ] && export FIREBASE_CREDENTIALS="$ROOT/finban-private.json"
[ -z "$GOOGLE_CLOUD_PROJECT" ] && export GOOGLE_CLOUD_PROJECT=finban-61e40

if [ "$MODE" = stop ]; then
  pkill -f "uvicorn app.main:app --host [0-9.]* --port $PORT" 2>/dev/null
  pkill -f "ngrok http" 2>/dev/null; pkill -f "cloudflared tunnel --url" 2>/dev/null
  command -v tailscale >/dev/null && tailscale funnel reset >/dev/null 2>&1
  sleep 1
  curl -fs -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && echo "port $PORT still answering (started by something else?)" || echo "stopped (port $PORT closed)"
  exit 0
fi
curl -fs -m 2 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && { echo "Something already answers on port $PORT. Run: $0 stop"; exit 1; }

if [ -z "$MODE" ]; then
  MODE=ngrok
fi
[ "$MODE" = ngrok ] && [ -z "$NGROK_DOMAIN" ] && NGROK_DOMAIN=galileo-tiliaceous-causally.ngrok-free.dev

PIDS=""
cleanup() {
  [ "$MODE" = funnel ] && tailscale funnel reset >/dev/null 2>&1
  for p in $PIDS; do kill "$p" 2>/dev/null; done
}
trap cleanup EXIT INT TERM

HOST=127.0.0.1; [ "$MODE" = local ] && HOST=0.0.0.0   # tunnels only need loopback; local mode is for the LAN
echo "AI explanation : $( [ -n "$OPENROUTER_API_KEY" ] && [ -n "$LLM_MODEL" ] && echo on || echo 'OFF (set OPENROUTER_API_KEY and LLM_MODEL)')"
echo "Photo check    : $( [ -n "$SIGHTENGINE_USER" ] && [ -n "$SIGHTENGINE_SECRET" ] && echo on || echo 'OFF (set SIGHTENGINE_USER and SIGHTENGINE_SECRET)')"
echo "Firebase auth  : $( [ -n "$FIREBASE_CREDENTIALS" ] && echo on || echo 'OFF (set FIREBASE_CREDENTIALS) - sign-in checks will fail')"
export DEMO_PAGES="${DEMO_PAGES:-1}"
.venv/bin/uvicorn app.main:app --host "$HOST" --port "$PORT" &
PIDS="$!"
i=0; until curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; do i=$((i+1)); [ $i -gt 30 ] && { echo "server did not start"; exit 1; }; sleep 0.5; done
echo "Server up on http://127.0.0.1:$PORT"

URL=""
case "$MODE" in
  local)
    IP="$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | cut -d' ' -f1)"
    echo "No tunnel. Same Wi-Fi: http://${IP:-<laptop-ip>}:$PORT   or USB: adb reverse tcp:$PORT tcp:$PORT then http://127.0.0.1:$PORT"
    ;;
  funnel)
    command -v tailscale >/dev/null || { echo "tailscale not installed"; exit 1; }
    tailscale funnel --bg "$PORT" >/dev/null 2>&1 || { echo "tailscale funnel failed. Is Tailscale connected, and Funnel enabled for this machine in the admin console?"; tailscale funnel status; exit 1; }
    URL="$(tailscale funnel status 2>/dev/null | grep -o 'https://[^ ]*' | head -1)"
    ;;
  ngrok)
    command -v ngrok >/dev/null || { echo "ngrok not installed (brew install ngrok, then ngrok config add-authtoken ...)"; exit 1; }
    if [ -n "$NGROK_DOMAIN" ]; then ngrok http --url="$NGROK_DOMAIN" "$PORT" >/dev/null & else ngrok http "$PORT" >/dev/null & fi
    PIDS="$PIDS $!"
    i=0; while [ -z "$URL" ] && [ $i -lt 20 ]; do i=$((i+1)); sleep 1; URL="$(curl -fs http://127.0.0.1:4040/api/tunnels 2>/dev/null | grep -o 'https://[^"]*' | head -1)"; done
    ;;
  cloudflared)
    command -v cloudflared >/dev/null || { echo "cloudflared not installed (brew install cloudflared)"; exit 1; }
    LOG="$(mktemp)"; cloudflared tunnel --url "http://127.0.0.1:$PORT" >"$LOG" 2>&1 &
    PIDS="$PIDS $!"
    i=0; while [ -z "$URL" ] && [ $i -lt 30 ]; do i=$((i+1)); sleep 1; URL="$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$LOG" | head -1)"; done
    ;;
  *) echo "unknown mode: $MODE"; exit 1;;
esac

if [ -n "$URL" ]; then
  if curl -fs -m 8 "$URL/health" >/dev/null 2>&1; then echo "Public URL (health OK): $URL"; else echo "Public URL: $URL (health check from here failed: tunnel may still be starting)"; fi
  echo "On each phone: Finban > Settings > Developer tools > Server address > $URL > Save > Test connection"
elif [ "$MODE" != local ]; then
  echo "Tunnel started but no URL found; check the tool's own output."
fi
wait

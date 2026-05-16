#!/system/bin/sh
set -u

BASE_DIR="${OPENCLAW_DLA_DAEMON_DIR:-/data/local/tmp/openclaw-dla-daemon}"
PID_FILE="${OPENCLAW_DLA_DAEMON_PID_FILE:-$BASE_DIR/openclaw-dla-bridge.pid}"
WORKER_PID_FILE="${OPENCLAW_DLA_WORKER_PID_FILE:-$BASE_DIR/openclaw-dla-worker.pid}"
SERVER_PID_FILE="${OPENCLAW_DLA_SERVER_PID_FILE:-$BASE_DIR/openclaw-dla-server.pid}"

stop_pid_file() {
  label="$1"
  file="$2"
  [ -r "$file" ] || return 0
  pid="$(cat "$file" 2>/dev/null)"
  [ -n "$pid" ] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    echo "stopping $label pid=$pid"
    kill "$pid" 2>/dev/null
    sleep 1
    if kill -0 "$pid" 2>/dev/null; then
      kill -9 "$pid" 2>/dev/null
    fi
  fi
  : > "$file" 2>/dev/null || true
}

[ "$(id -u)" = "0" ] || {
  echo "openclaw-dla-daemon: stop must run as root" >&2
  exit 1
}

stop_pid_file "DLA worker" "$WORKER_PID_FILE"
stop_pid_file "DLA server" "$SERVER_PID_FILE"
stop_pid_file "DLA bridge" "$PID_FILE"
echo "openclaw-dla-daemon stopped"

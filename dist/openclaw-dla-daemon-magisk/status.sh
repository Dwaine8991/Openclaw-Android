#!/system/bin/sh
set -u

BASE_DIR="${OPENCLAW_DLA_DAEMON_DIR:-/data/local/tmp/openclaw-dla-daemon}"
PID_FILE="${OPENCLAW_DLA_DAEMON_PID_FILE:-$BASE_DIR/openclaw-dla-bridge.pid}"
WORKER_PID_FILE="${OPENCLAW_DLA_WORKER_PID_FILE:-$BASE_DIR/openclaw-dla-worker.pid}"
SERVER_PID_FILE="${OPENCLAW_DLA_SERVER_PID_FILE:-$BASE_DIR/openclaw-dla-server.pid}"
LOG_FILE="${OPENCLAW_DLA_DAEMON_LOG_FILE:-$BASE_DIR/openclaw-dla-daemon.log}"

show_pid() {
  label="$1"
  file="$2"
  pid=""
  [ -r "$file" ] && pid="$(cat "$file" 2>/dev/null)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "$label: running pid=$pid"
  else
    echo "$label: stopped"
  fi
}

show_pid "bridge" "$PID_FILE"
show_pid "worker" "$WORKER_PID_FILE"
show_pid "server" "$SERVER_PID_FILE"
echo "log: $LOG_FILE"
[ -r "$LOG_FILE" ] && tail -n 20 "$LOG_FILE"

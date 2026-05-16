#!/system/bin/sh
set -u

APP_PACKAGE="${OPENCLAW_ANDROID_PACKAGE:-io.github.openclawcn.app}"
BASE_DIR="${OPENCLAW_DLA_DAEMON_DIR:-/data/local/tmp/openclaw-dla-daemon}"
LLM_DIR="${OPENCLAW_DLA_LLM_DIR:-/data/local/tmp/llm_sdk}"
BRIDGE_FILE="${OPENCLAW_DLA_BRIDGE_FILE:-$BASE_DIR/android-dla-bridge.mjs}"
PID_FILE="${OPENCLAW_DLA_DAEMON_PID_FILE:-$BASE_DIR/openclaw-dla-bridge.pid}"
WORKER_PID_FILE="${OPENCLAW_DLA_WORKER_PID_FILE:-$BASE_DIR/openclaw-dla-worker.pid}"
SERVER_PID_FILE="${OPENCLAW_DLA_SERVER_PID_FILE:-$BASE_DIR/openclaw-dla-server.pid}"
LOG_FILE="${OPENCLAW_DLA_DAEMON_LOG_FILE:-$BASE_DIR/openclaw-dla-daemon.log}"
PROMPT_DIR="${OPENCLAW_DLA_PROMPT_DIR:-$BASE_DIR/prompts}"
PORT="${OPENCLAW_DLA_PORT:-8081}"

die() {
  echo "openclaw-dla-daemon: $*" >&2
  exit 1
}

is_running() {
  [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

find_node_bin() {
  if [ -n "${OPENCLAW_DLA_NODE_BIN:-}" ] && [ -x "$OPENCLAW_DLA_NODE_BIN" ]; then
    echo "$OPENCLAW_DLA_NODE_BIN"
    return 0
  fi

  if [ -x "$BASE_DIR/libopenclaw_node.so" ]; then
    echo "$BASE_DIR/libopenclaw_node.so"
    return 0
  fi

  apk_path="$(pm path "$APP_PACKAGE" 2>/dev/null | sed -n '1s/^package://p')"
  if [ -n "$apk_path" ]; then
    app_dir="${apk_path%/*}"
    for candidate in "$app_dir/lib/arm64/libopenclaw_node.so" "$app_dir/lib/arm64-v8a/libopenclaw_node.so"; do
      if [ -x "$candidate" ]; then
        echo "$candidate"
        return 0
      fi
    done
  fi

  return 1
}

[ "$(id -u)" = "0" ] || die "must be started as root, for example: adb shell su 0 sh $BASE_DIR/start.sh"

mkdir -p "$BASE_DIR" "$PROMPT_DIR" || die "cannot create $BASE_DIR"
[ -r "$BRIDGE_FILE" ] || die "missing bridge file: $BRIDGE_FILE"
[ -x "$LLM_DIR/main" ] || die "missing executable DLA main: $LLM_DIR/main"
[ -r "$LLM_DIR/config_np8-qwen3-1.7b.yaml" ] || die "missing DLA config: $LLM_DIR/config_np8-qwen3-1.7b.yaml"

if [ -r "$PID_FILE" ]; then
  old_pid="$(cat "$PID_FILE" 2>/dev/null)"
  if is_running "$old_pid"; then
    echo "openclaw-dla-daemon already running: pid=$old_pid port=$PORT"
    exit 0
  fi
fi

NODE_BIN="$(find_node_bin)" || die "cannot find libopenclaw_node.so; install OpenClaw or deploy fallback node binary"

export OPENCLAW_ANDROID_PACKAGE="$APP_PACKAGE"
export OPENCLAW_DLA_HOST="${OPENCLAW_DLA_HOST:-127.0.0.1}"
export OPENCLAW_DLA_PORT="$PORT"
export OPENCLAW_DLA_LLM_DIR="$LLM_DIR"
export OPENCLAW_DLA_MAIN_BIN="${OPENCLAW_DLA_MAIN_BIN:-$LLM_DIR/main}"
export OPENCLAW_DLA_CONFIG="${OPENCLAW_DLA_CONFIG:-config_np8-qwen3-1.7b.yaml}"
export OPENCLAW_DLA_MODEL_ID="${OPENCLAW_DLA_MODEL_ID:-qwen3-1.7b-dla}"
export OPENCLAW_DLA_PREFORMATTER="${OPENCLAW_DLA_PREFORMATTER:-Qwen3NoInputNoThink}"
export OPENCLAW_DLA_MAX_TOKENS="${OPENCLAW_DLA_MAX_TOKENS:-256}"
export OPENCLAW_DLA_MIN_AVAILABLE_KB="${OPENCLAW_DLA_MIN_AVAILABLE_KB:-1250000}"
export OPENCLAW_DLA_APP_PSS_LIMIT_KB="${OPENCLAW_DLA_APP_PSS_LIMIT_KB:-2200000}"
export OPENCLAW_DLA_TIMEOUT_MS="${OPENCLAW_DLA_TIMEOUT_MS:-120000}"
export OPENCLAW_DLA_WORKER_PID_FILE="$WORKER_PID_FILE"
export OPENCLAW_DLA_PERSISTENT_MODE="${OPENCLAW_DLA_PERSISTENT_MODE:-auto}"
export OPENCLAW_DLA_PERSISTENT_STREAM="${OPENCLAW_DLA_PERSISTENT_STREAM:-true}"
export OPENCLAW_DLA_SERVER_BIN="${OPENCLAW_DLA_SERVER_BIN:-$LLM_DIR/openclaw-qwen-dla-server}"
export OPENCLAW_DLA_SERVER_HOST="${OPENCLAW_DLA_SERVER_HOST:-127.0.0.1}"
export OPENCLAW_DLA_SERVER_PORT="${OPENCLAW_DLA_SERVER_PORT:-18082}"
export OPENCLAW_DLA_SERVER_PID_FILE="$SERVER_PID_FILE"
export OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS="${OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS:-600000}"
export OPENCLAW_DLA_SERVER_REQUEST_TIMEOUT_MS="${OPENCLAW_DLA_SERVER_REQUEST_TIMEOUT_MS:-30000}"
export OPENCLAW_DLA_PROMPT_DIR="$PROMPT_DIR"
export LD_LIBRARY_PATH="$LLM_DIR:$BASE_DIR:${LD_LIBRARY_PATH:-}"

touch "$LOG_FILE" || die "cannot write log: $LOG_FILE"
chmod 600 "$LOG_FILE" 2>/dev/null

echo "starting openclaw-dla-daemon: node=$NODE_BIN bridge=$BRIDGE_FILE port=$PORT" >> "$LOG_FILE"
nohup "$NODE_BIN" "$BRIDGE_FILE" >> "$LOG_FILE" 2>&1 &
daemon_pid="$!"
echo "$daemon_pid" > "$PID_FILE"
sleep 1

if ! is_running "$daemon_pid"; then
  tail -n 80 "$LOG_FILE" >&2
  die "daemon exited during startup"
fi

echo "openclaw-dla-daemon started: pid=$daemon_pid port=$PORT log=$LOG_FILE"

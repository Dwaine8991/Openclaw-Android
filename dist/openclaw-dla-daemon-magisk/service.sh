#!/system/bin/sh

MODDIR="${0%/*}"
export OPENCLAW_DLA_DAEMON_DIR="$MODDIR"
export OPENCLAW_DLA_BRIDGE_FILE="$MODDIR/android-dla-bridge.mjs"
export OPENCLAW_DLA_DAEMON_LOG_FILE="/data/local/tmp/openclaw-dla-daemon/openclaw-dla-daemon.log"
export OPENCLAW_DLA_DAEMON_PID_FILE="/data/local/tmp/openclaw-dla-daemon/openclaw-dla-bridge.pid"
export OPENCLAW_DLA_WORKER_PID_FILE="/data/local/tmp/openclaw-dla-daemon/openclaw-dla-worker.pid"
export OPENCLAW_DLA_PROMPT_DIR="/data/local/tmp/openclaw-dla-daemon/prompts"
export OPENCLAW_DLA_SERVER_BIN="$MODDIR/openclaw-qwen-dla-server"

mkdir -p /data/local/tmp/openclaw-dla-daemon
chmod 755 "$MODDIR/openclaw-qwen-dla-server" 2>/dev/null
sleep 20
sh "$MODDIR/start.sh" >> "$OPENCLAW_DLA_DAEMON_LOG_FILE" 2>&1

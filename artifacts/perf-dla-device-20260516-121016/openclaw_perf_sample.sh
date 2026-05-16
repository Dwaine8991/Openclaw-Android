#!/system/bin/sh
OUT_DIR="/data/local/tmp/openclaw-perf-run"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/samples.csv"
THERM="$OUT_DIR/thermal.csv"
NPU="$OUT_DIR/npu.txt"
TOP="$OUT_DIR/top.txt"
PSOUT="$OUT_DIR/ps.txt"
rm -f "$OUT" "$THERM" "$NPU" "$TOP" "$PSOUT"
echo "sample,epoch_ms,mem_avail_kb,total_jiffies,app_pid,app_jiffies,app_rss_kb,app_pss_kb,bridge_pid,bridge_jiffies,bridge_rss_kb,server_pid,server_jiffies,server_rss_kb,worker_pid,worker_jiffies,worker_rss_kb,mdla_cur_freq,mdla_busy_time,mdla_total_time" > "$OUT"
echo "sample,epoch_ms,zone,type,temp" > "$THERM"
read_total() { awk '/^cpu /{s=0; for(i=2;i<=NF;i++) s+=$i; print s}' /proc/stat; }
read_jiffies() { [ -n "$1" ] && [ -r "/proc/$1/stat" ] && awk '{print $14+$15+$16+$17}' "/proc/$1/stat" || echo 0; }
read_rss() { [ -n "$1" ] && [ -r "/proc/$1/status" ] && awk '/VmRSS:/{print $2}' "/proc/$1/status" || echo 0; }
read_pss() { [ -n "$1" ] && [ -r "/proc/$1/smaps_rollup" ] && awk '/^Pss:/{print $2}' "/proc/$1/smaps_rollup" || echo 0; }
first_pid() { pidof "$1" 2>/dev/null | awk '{print $1}'; }
read_file() { [ -r "$1" ] && cat "$1" 2>/dev/null | tr -d '\r\n' || echo ""; }
for i in $(seq 1 35); do
  now=$(date +%s%3N 2>/dev/null || date +%s000)
  mem=$(awk '/MemAvailable:/{print $2}' /proc/meminfo)
  total=$(read_total)
  app=$(first_pid io.github.openclawcn.app)
  bridge=$(cat /data/local/tmp/openclaw-dla-daemon/openclaw-dla-bridge.pid 2>/dev/null)
  server=$(first_pid openclaw-qwen-dla-server)
  worker=$(cat /data/local/tmp/openclaw-dla-daemon/openclaw-dla-worker.pid 2>/dev/null)
  mdla_cur=$(read_file /sys/class/devfreq/soc:mdla-devfreq/cur_freq)
  mdla_busy=$(read_file /sys/class/devfreq/soc:mdla-devfreq/trans_stat)
  mdla_total=""
  echo "$i,$now,$mem,$total,$app,$(read_jiffies "$app"),$(read_rss "$app"),$(read_pss "$app"),$bridge,$(read_jiffies "$bridge"),$(read_rss "$bridge"),$server,$(read_jiffies "$server"),$(read_rss "$server"),$worker,$(read_jiffies "$worker"),$(read_rss "$worker"),$mdla_cur,$mdla_busy,$mdla_total" >> "$OUT"
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/temp" ] || continue
    typ=$(cat "$z/type" 2>/dev/null | tr -d '\r\n')
    tmp=$(cat "$z/temp" 2>/dev/null | tr -d '\r\n')
    echo "$i,$now,${z##*/},$typ,$tmp" >> "$THERM"
  done
  {
    echo "===== sample $i $now ====="
    top -b -n 1 2>/dev/null | head -n 35
  } >> "$TOP"
  {
    echo "===== sample $i $now ====="
    ps -A 2>/dev/null | grep -E 'openclaw|qwen|dla|llm|io.github.openclawcn.app'
  } >> "$PSOUT"
  {
    echo "===== sample $i $now ====="
    for f in /sys/kernel/thermal/apu_info /sys/kernel/thermal/is_apu_limit /sys/kernel/thermal/apu_atc /sys/class/devfreq/soc:mdla-devfreq/cur_freq /sys/class/devfreq/soc:mdla-devfreq/available_frequencies /sys/class/devfreq/soc:mdla-devfreq/trans_stat /sys/kernel/debug/mdla/mdla_memory; do
      [ -r "$f" ] && echo "--- $f" && cat "$f" 2>/dev/null | head -n 80
    done
  } >> "$NPU"
  sleep 1
done

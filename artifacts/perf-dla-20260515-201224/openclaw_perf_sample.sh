#!/system/bin/sh
OUT_DIR=/data/local/tmp/openclaw_perf_run
mkdir -p "$OUT_DIR"
CSV="$OUT_DIR/samples.csv"
TOP="$OUT_DIR/top_samples.txt"
PS="$OUT_DIR/ps_samples.txt"
THERM="$OUT_DIR/thermal_zones.txt"
APUP="$OUT_DIR/apusys_power_samples.txt"
DURATION=${1:-120}
INTERVAL=${2:-1}
PACKAGE=io.github.openclawcn.app

echo "ts,elapsed_s,mem_total_kb,mem_free_kb,mem_available_kb,swap_free_kb,mdla_cur_hz,mdla_target_hz,apu_temp_mC,cpu_temp_max_mC,app_pid,app_rss_kb,app_vsz_kb,daemon_pids" > "$CSV"
: > "$TOP"
: > "$PS"
: > "$APUP"
for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ] || continue
  echo "${z##*/},$(cat "$z/type" 2>/dev/null),$(cat "$z/temp" 2>/dev/null)" >> "$THERM"
done
START=$(date +%s)
END=$((START + DURATION))
while [ "$(date +%s)" -le "$END" ]; do
  NOW=$(date +%s)
  ELAPSED=$((NOW - START))
  MEM_TOTAL=$(awk '/MemTotal:/ {print $2}' /proc/meminfo)
  MEM_FREE=$(awk '/MemFree:/ {print $2}' /proc/meminfo)
  MEM_AVAIL=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
  SWAP_FREE=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
  MDLA_CUR=$(cat /sys/class/devfreq/soc:mdla-devfreq/cur_freq 2>/dev/null)
  MDLA_TARGET=$(cat /sys/class/devfreq/soc:mdla-devfreq/target_freq 2>/dev/null)
  APU_TEMP=""
  CPU_MAX=""
  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    TYPE=$(cat "$z/type" 2>/dev/null)
    TEMP=$(cat "$z/temp" 2>/dev/null)
    case "$TYPE" in
      apu) APU_TEMP="$TEMP" ;;
      cpu-*) if [ -z "$CPU_MAX" ] || [ "$TEMP" -gt "$CPU_MAX" ]; then CPU_MAX="$TEMP"; fi ;;
    esac
  done
  APP_PID=$(pidof "$PACKAGE" 2>/dev/null | awk '{print $1}')
  APP_RSS=""
  APP_VSZ=""
  if [ -n "$APP_PID" ]; then
    APP_RSS=$(awk '/VmRSS:/ {print $2}' /proc/$APP_PID/status 2>/dev/null)
    APP_VSZ=$(awk '/VmSize:/ {print $2}' /proc/$APP_PID/status 2>/dev/null)
  fi
  DAEMON_PIDS=$(ps -A -o PID,NAME 2>/dev/null | awk '/libopenclaw_node|llm_sdk|openclaw-dla|\/main$| main$/ {printf "%s:%s;", $1, $2}')
  echo "$NOW,$ELAPSED,$MEM_TOTAL,$MEM_FREE,$MEM_AVAIL,$SWAP_FREE,$MDLA_CUR,$MDLA_TARGET,$APU_TEMP,$CPU_MAX,$APP_PID,$APP_RSS,$APP_VSZ,$DAEMON_PIDS" >> "$CSV"
  {
    echo "===== $NOW elapsed=$ELAPSED ====="
    top -b -n 1 -o PID,USER,%CPU,RES,ARGS 2>/dev/null | grep -E "$PACKAGE|libopenclaw_node|llm_sdk|/main| main$|TOTAL|Tasks|Mem:|Swap:|cpu" || true
  } >> "$TOP"
  {
    echo "===== $NOW elapsed=$ELAPSED ====="
    ps -A -o PID,PPID,USER,NAME,RSS,VSZ 2>/dev/null | grep -E "$PACKAGE|libopenclaw_node|llm_sdk|/main| main$" || true
  } >> "$PS"
  {
    echo "===== $NOW elapsed=$ELAPSED ====="
    cat /sys/kernel/debug/apusys/power 2>/dev/null || true
    echo "mdla_trans_stat"
    cat /sys/class/devfreq/soc:mdla-devfreq/trans_stat 2>/dev/null || true
  } >> "$APUP"
  sleep "$INTERVAL"
done
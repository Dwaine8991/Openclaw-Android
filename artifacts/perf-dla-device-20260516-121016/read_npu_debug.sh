#!/system/bin/sh
for f in /sys/kernel/debug/mdla/info /sys/kernel/debug/mdla/profiling /sys/kernel/debug/mdla/pmu_trace /sys/kernel/debug/mdla/preempt_times /sys/kernel/debug/mdla/poweroff_time /sys/kernel/debug/mdla/version /sys/kernel/debug/apusys/power /sys/kernel/debug/apusys/midware/pwroff_cnt; do
  echo "--- $f"
  cat "$f" 2>/dev/null | head -n 120
done
#!/system/bin/sh
TRACE=/sys/kernel/tracing
[ -d "$TRACE" ] || TRACE=/sys/kernel/debug/tracing
echo 0 > "$TRACE/tracing_on"
echo > "$TRACE/trace"
for e in "$TRACE/events/met_mdlasys_events/mdla_cmd_enter/enable" "$TRACE/events/met_mdlasys_events/mdla_cmd_leave/enable" "$TRACE/events/met_mdlasys_events/mdla_polling/enable" "$TRACE/events/apupwr_events/apupwr_dvfs/enable" "$TRACE/events/apupwr_events/apupwr_pwr/enable" "$TRACE/events/mtk_thermal/thermal_apu/enable"; do
  [ -w "$e" ] && echo 1 > "$e"
done
echo 1 > "$TRACE/tracing_on"
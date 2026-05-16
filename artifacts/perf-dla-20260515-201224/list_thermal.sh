#!/system/bin/sh
for z in /sys/class/thermal/thermal_zone*; do
  [ -d "$z" ] || continue
  name=${z##*/}
  type=$(cat "$z/type" 2>/dev/null | tr -d '\r')
  temp=$(cat "$z/temp" 2>/dev/null | tr -d '\r')
  echo "$name $type temp=$temp"
done
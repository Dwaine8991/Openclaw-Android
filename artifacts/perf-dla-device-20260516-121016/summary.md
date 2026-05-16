# DLA device performance run

Flow: cold-start root DLA daemon, POST stream request to `http://127.0.0.1:18081/v1/messages`, `max_tokens=128`.

Key timings from daemon log:
- server ready: 5663 ms
- first streamed delta: 8177 ms
- persistent stream complete: 20108 ms
- curl total: 20198 ms

CPU/RSS highlights:
- `openclaw-qwen-dla-server`: peak top CPU 97.0%; jiffies estimate peak 94.8% of one core; RSS peak 490.8 MB, then settled around 104.6 MB.
- `libopenclaw_node.so` bridge: peak top CPU 5.8%; jiffies estimate peak 9.8% of one core; RSS about 46.7-47.7 MB.
- `io.github.openclawcn.app`: mostly idle during DLA; jiffies estimate peak 2.8% of one core; RSS about 261 MB; sampled PSS about 162-163 MB.
- App dumpsys meminfo: TOTAL PSS 143.8 MB before run, 181.7 MB after run.
- System MemAvailable: 5465 MB before DLA server start, minimum sampled 3660 MB.

Thermal peaks:
- APU thermal zone peak: 60.3 C
- CPU little cluster peak: 61.1 C
- CPU big cluster peak: 59.5 C
- soc_max peak: 60.9 C

NPU/APUSYS notes:
- `/sys/class/devfreq/soc:mdla-devfreq/cur_freq` stayed readable at 900 MHz during samples.
- `/sys/kernel/thermal/is_apu_limit` stayed 0 in sampled NPU snapshots, so no APU thermal limit was reported by that node.
- `/sys/kernel/debug/apusys/power` exposed MDLA OPP/frequency table, but not live utilization percent.
- ftrace capture for `mdla_cmd_enter/leave`, `apupwr_*`, and `thermal_apu` produced no matching events in this run, so this firmware does not currently expose a reliable NPU utilization percentage through those enabled tracepoints.

Artifacts:
- samples: `openclaw-perf-run/samples.csv`
- thermal: `openclaw-perf-run/thermal.csv`
- process/top snapshots: `openclaw-perf-run/top.txt`, `openclaw-perf-run/ps.txt`
- NPU snapshots: `openclaw-perf-run/npu.txt`, `npu-debug-after.txt`, `npu-trace.txt`
- daemon logs: `daemon-status-after-goodrun.txt`

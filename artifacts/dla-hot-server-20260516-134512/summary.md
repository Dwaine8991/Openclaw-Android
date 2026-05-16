# DLA hot persistent server comparison

Change under test: `OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS` default increased from 120000 ms to 600000 ms.

Same prompt, `max_tokens=128`, two requests back to back through `http://127.0.0.1:18081/v1/messages`.

| round | server | curl total | first delta | completion |
| --- | --- | ---: | ---: | ---: |
| 1 | cold start | 18.2 s | 6.66 s | 18.14 s |
| 2 | reused persistent server | 13.5 s | 1.95 s | 13.45 s |

Effect:
- Server ready path dropped from 4.27 s to 0.015 s (`reused=true`).
- First streamed token improved by about 4.7 s.
- Total response improved by about 4.7 s.
- Server remained running after the second request, confirming the 10-minute idle window is active.

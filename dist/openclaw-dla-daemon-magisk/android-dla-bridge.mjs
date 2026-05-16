import { createServer, request } from "node:http";
import { spawn, execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const host = process.env.OPENCLAW_DLA_HOST ?? "127.0.0.1";
const port = Number(process.env.OPENCLAW_DLA_PORT ?? "8081");
const llmDir = process.env.OPENCLAW_DLA_LLM_DIR ?? "/data/local/tmp/llm_sdk";
const mainBin = process.env.OPENCLAW_DLA_MAIN_BIN ?? join(llmDir, "main");
const promptDir = process.env.OPENCLAW_DLA_PROMPT_DIR ?? llmDir;
const config = process.env.OPENCLAW_DLA_CONFIG ?? "config_np8-qwen3-1.7b.yaml";
const modelId = process.env.OPENCLAW_DLA_MODEL_ID ?? "qwen3-1.7b-dla";
const preformatter = process.env.OPENCLAW_DLA_PREFORMATTER ?? "Qwen3NoInputNoThink";
const defaultMaxTokens = Number(process.env.OPENCLAW_DLA_MAX_TOKENS ?? "256");
const maxInputChars = Number(process.env.OPENCLAW_DLA_MAX_INPUT_CHARS ?? "2800");
const maxUserChars = Number(process.env.OPENCLAW_DLA_MAX_USER_CHARS ?? "1600");
const maxProductContextChars = Number(process.env.OPENCLAW_DLA_MAX_PRODUCT_CONTEXT_CHARS ?? "900");
const streamChunkChars = Number(process.env.OPENCLAW_DLA_STREAM_CHUNK_CHARS ?? "12");
const streamChunkDelayMs = Number(process.env.OPENCLAW_DLA_STREAM_CHUNK_DELAY_MS ?? "60");
const salesSystemPrompt = process.env.OPENCLAW_DLA_SALES_SYSTEM_PROMPT ??
  "你是门店导购助手。用简短中文回答，先理解用户需求，再推荐合适商品。" +
  "不要编造库存、价格和参数；不确定时请询问补充信息。";
const minAvailableKb = Number(process.env.OPENCLAW_DLA_MIN_AVAILABLE_KB ?? "1250000");
const appPssLimitKb = Number(process.env.OPENCLAW_DLA_APP_PSS_LIMIT_KB ?? "2200000");
const timeoutMs = Number(process.env.OPENCLAW_DLA_TIMEOUT_MS ?? "120000");
const workerPidFile = process.env.OPENCLAW_DLA_WORKER_PID_FILE;
const persistentMode = (process.env.OPENCLAW_DLA_PERSISTENT_MODE ?? "off").toLowerCase();
const persistentStreamMode = (process.env.OPENCLAW_DLA_PERSISTENT_STREAM ?? "false").toLowerCase();
const persistentServerBin = process.env.OPENCLAW_DLA_SERVER_BIN ?? join(llmDir, "openclaw-qwen-dla-server");
const persistentServerHost = process.env.OPENCLAW_DLA_SERVER_HOST ?? "127.0.0.1";
const persistentServerPort = Number(process.env.OPENCLAW_DLA_SERVER_PORT ?? "18082");
const persistentServerPidFile = process.env.OPENCLAW_DLA_SERVER_PID_FILE;
const persistentServerIdleTimeoutMs = Number(process.env.OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS ?? "600000");
const persistentServerRequestTimeoutMs = Number(process.env.OPENCLAW_DLA_SERVER_REQUEST_TIMEOUT_MS ?? "30000");
const appPackage = process.env.OPENCLAW_ANDROID_PACKAGE ?? "io.github.openclawcn.app";
let active = false;
let persistentServerChild = null;
let persistentServerStarting = null;
let persistentServerIdleTimer = null;

function perfNow() {
  return Date.now();
}

function perfLog(requestId, phase, startedAt, extra = "") {
  const now = perfNow();
  const suffix = extra ? " " + extra : "";
  console.log(`[openclaw-dla-perf] id=${requestId} phase=${phase} t=${now} elapsedMs=${now - startedAt}${suffix}`);
}

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

function error(res, status, type, message) {
  json(res, status, { error: { type, message } });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      data += chunk;
      if (data.length > 1024 * 1024) {
        reject(new Error("request_too_large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

function availableMemoryKb() {
  const meminfo = readFileSync("/proc/meminfo", "utf8");
  const match = meminfo.match(/^MemAvailable:\s+(\d+)\s+kB/m);
  return match ? Number(match[1]) : Number.MAX_SAFE_INTEGER;
}

function appPssKb() {
  try {
    const output = execFileSync("/system/bin/dumpsys", ["meminfo", appPackage], {
      encoding: "utf8",
      timeout: 3000,
    });
    const match = output.match(/^\s*TOTAL\s+(\d+)/m);
    if (match) return Number(match[1]);
  } catch {
    // App-owned bridge processes often cannot use dumpsys; fall back to procfs.
  }
  try {
    const pid = execFileSync("/system/bin/pidof", ["-s", appPackage], {
      encoding: "utf8",
      timeout: 1000,
    }).trim();
    if (!pid) return 0;
    const rollup = readFileSync(`/proc/${pid}/smaps_rollup`, "utf8");
    const match = rollup.match(/^Pss:\s+(\d+)\s+kB/m);
    return match ? Number(match[1]) : 0;
  } catch {
    return 0;
  }
}

function assertMemoryBudget() {
  const available = availableMemoryKb();
  if (available < minAvailableKb) {
    const err = new Error("LOW_MEMORY: MemAvailable=" + available + "kB");
    err.code = "LOW_MEMORY";
    throw err;
  }
  const pss = appPssKb();
  if (pss > appPssLimitKb) {
    const err = new Error("LOW_MEMORY: app PSS=" + pss + "kB");
    err.code = "LOW_MEMORY";
    throw err;
  }
}

function persistentServerEnabled() {
  return persistentMode === "auto" || persistentMode === "server" || persistentMode === "on" || persistentMode === "1";
}

function persistentStreamEnabled() {
  return persistentStreamMode === "true" || persistentStreamMode === "on" || persistentStreamMode === "1";
}

function persistentServerEnv() {
  return {
    ...process.env,
    LLM_DIR: llmDir,
    CONFIG: config,
    LD_LIBRARY_PATH: (process.env.LD_LIBRARY_PATH ?? "") + ":" + llmDir,
  };
}

function clearPersistentServerIdleTimer() {
  if (persistentServerIdleTimer) {
    clearTimeout(persistentServerIdleTimer);
    persistentServerIdleTimer = null;
  }
}

function schedulePersistentServerIdleStop() {
  clearPersistentServerIdleTimer();
  if (persistentServerIdleTimeoutMs <= 0) return;
  persistentServerIdleTimer = setTimeout(() => {
    stopPersistentServer("idle_timeout");
  }, persistentServerIdleTimeoutMs);
  persistentServerIdleTimer.unref?.();
}

function stopPersistentServer(reason = "stop") {
  clearPersistentServerIdleTimer();
  const child = persistentServerChild;
  persistentServerChild = null;
  if (persistentServerPidFile) writeFileSync(persistentServerPidFile, "", "utf8");
  if (!child || child.killed) return;
  console.log(`[openclaw-dla-perf] persistent_server_stop reason=${reason} pid=${child.pid ?? ""}`);
  child.kill("SIGTERM");
  setTimeout(() => child.kill("SIGKILL"), 800).unref?.();
}

function httpJson(path, body = null, timeout = persistentServerRequestTimeoutMs) {
  return new Promise((resolve, reject) => {
    const payload = body == null ? "" : JSON.stringify(body);
    const req = request(
      {
        host: persistentServerHost,
        port: persistentServerPort,
        path,
        method: body == null ? "GET" : "POST",
        headers: body == null
          ? {}
          : {
            "content-type": "application/json; charset=utf-8",
            "content-length": Buffer.byteLength(payload),
          },
      },
      res => {
        let data = "";
        res.setEncoding("utf8");
        res.on("data", chunk => {
          data += chunk;
          if (data.length > 2 * 1024 * 1024) {
            req.destroy(new Error("persistent_response_too_large"));
          }
        });
        res.on("end", () => resolve({ statusCode: res.statusCode ?? 0, body: data, headers: res.headers }));
      },
    );
    req.setTimeout(timeout, () => req.destroy(new Error("persistent_server_timeout")));
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function isPersistentServerReady() {
  try {
    const res = await httpJson("/v1/models", null, 1200);
    return res.statusCode >= 200 && res.statusCode < 300;
  } catch {
    return false;
  }
}

async function waitForPersistentServer(timeout = 6000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await isPersistentServerReady()) return true;
    await sleep(250);
  }
  return false;
}

async function ensurePersistentServer(requestId, startedAt) {
  if (!persistentServerEnabled()) return false;
  clearPersistentServerIdleTimer();
  if (await isPersistentServerReady()) {
    perfLog(requestId, "persistent_server_ready", startedAt, "reused=true");
    return true;
  }
  if (!existsSync(persistentServerBin)) {
    perfLog(requestId, "persistent_server_unavailable", startedAt, `path=${persistentServerBin}`);
    return false;
  }
  if (persistentServerStarting) return persistentServerStarting;
  assertMemoryBudget();
  persistentServerStarting = (async () => {
    perfLog(requestId, "persistent_server_starting", startedAt, `bin=${persistentServerBin} port=${persistentServerPort}`);
    const child = spawn(
      persistentServerBin,
      [String(persistentServerPort)],
      {
        cwd: llmDir,
        env: persistentServerEnv(),
        stdio: ["ignore", "pipe", "pipe"],
      },
    );
    persistentServerChild = child;
    if (persistentServerPidFile) writeFileSync(persistentServerPidFile, String(child.pid ?? ""), "utf8");
    child.stdout.on("data", chunk => console.log("[openclaw-dla-server] " + chunk.toString().trimEnd()));
    child.stderr.on("data", chunk => console.log("[openclaw-dla-server] " + chunk.toString().trimEnd()));
    child.on("close", code => {
      if (persistentServerChild === child) persistentServerChild = null;
      if (persistentServerPidFile) writeFileSync(persistentServerPidFile, "", "utf8");
      console.log(`[openclaw-dla-perf] persistent_server_closed code=${code ?? ""} pid=${child.pid ?? ""}`);
    });
    const ready = await waitForPersistentServer();
    perfLog(requestId, ready ? "persistent_server_ready" : "persistent_server_not_ready", startedAt, `pid=${child.pid ?? ""}`);
    if (!ready) stopPersistentServer("not_ready");
    return ready;
  })();
  try {
    return await persistentServerStarting;
  } finally {
    persistentServerStarting = null;
  }
}

function contentText(content) {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return content
      .map(part => (typeof part === "string" ? part : part?.type === "text" ? part.text ?? "" : ""))
      .filter(Boolean)
      .join("\n");
  }
  return "";
}

function compactText(text) {
  return (text ?? "")
    .replace(/\r/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function truncateText(text, limit) {
  const compact = compactText(text);
  if (compact.length <= limit) return compact;
  return compact.slice(0, Math.max(0, limit - 12)).trimEnd() + "\n[已截断]";
}

function latestUserText(messages) {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if ((message.role ?? "user") === "user") {
      return contentText(message.content);
    }
  }
  return "";
}

function productContextFromMessages(messages, systemText = "") {
  const chunks = [];
  const maybeAdd = text => {
    const compact = compactText(text);
    if (!compact) return;
    const lines = compact
      .split(/\n|(?<=[。！？.!?])\s+/)
      .map(line => line.trim())
      .filter(Boolean)
      .map(line => {
        const productMarker = line.search(/产品资料[:：]|商品资料[:：]|已知资料[:：]/);
        return productMarker >= 0 ? line.slice(productMarker) : line;
      })
      .filter(line => /产品|商品|库存|价格|售价|规格|型号|参数|导购|门店|优惠|品牌|SKU|Agenew/i.test(line))
      .filter(line => !/tool_schema|function_call|parameters|properties|required|\$schema/i.test(line));
    if (lines.length) chunks.push(lines.join("\n"));
  };

  maybeAdd(systemText);
  for (const message of messages) {
    const role = message.role ?? "user";
    if (role === "tool" || role === "function") {
      maybeAdd(contentText(message.content));
    }
  }
  return truncateText(chunks.join("\n"), maxProductContextChars);
}

function buildDlaPrompt(messages, systemText = "") {
  const user = truncateText(latestUserText(messages), maxUserChars);
  const productContext = productContextFromMessages(messages, systemText);
  const contextBlock = productContext ? "\n\n已知资料:\n" + productContext : "";
  let prompt = salesSystemPrompt + contextBlock + "\n\n用户问题:\n" + user + "\n\n请直接给出导购回答:";

  if (prompt.length > maxInputChars && productContext) {
    const fixedChars = prompt.length - productContext.length;
    const budget = Math.max(0, maxInputChars - fixedChars - 12);
    const shorterContext = truncateText(productContext, budget);
    const shorterBlock = shorterContext ? "\n\n已知资料:\n" + shorterContext : "";
    prompt = salesSystemPrompt + shorterBlock + "\n\n用户问题:\n" + user + "\n\n请直接给出导购回答:";
  }

  if (prompt.length > maxInputChars) {
    const err = new Error("DLA prompt too large after compaction: " + prompt.length + " chars");
    err.code = "CONTEXT_OVERFLOW";
    throw err;
  }
  return prompt;
}

function promptFromAnthropic(body) {
  return buildDlaPrompt(body.messages ?? [], contentText(body.system));
}

function promptFromOpenAi(body) {
  return buildDlaPrompt(body.messages ?? []);
}

function parseDlaOutput(output) {
  const full = output.match(/\[Full Response\]\s*([\s\S]*?)(?:\n\[Latency\]|\r?\n?$)/);
  if (full) return full[1].trim();
  const lines = output.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  return lines.at(-1) ?? "";
}

function persistentRequestBody(prompt, maxTokens, stream) {
  return {
    model: modelId,
    messages: [{ role: "user", content: prompt }],
    max_tokens: maxTokens,
    stream,
  };
}

function parseOpenAiText(body) {
  const parsed = JSON.parse(body || "{}");
  const choice = parsed.choices?.[0];
  return choice?.message?.content ?? choice?.delta?.content ?? choice?.text ?? "";
}

function openAiStreamDelta(payload) {
  const choice = payload?.choices?.[0];
  return choice?.delta?.content ?? choice?.message?.content ?? choice?.text ?? "";
}

function callPersistentServerStream(prompt, maxTokens, res, requestId, startedAt) {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(persistentRequestBody(prompt, maxTokens, true));
    let stream = null;
    let output = "";
    let streamed = "";
    let sseBuffer = "";
    let firstDeltaLogged = false;
    let settled = false;
    const fail = err => {
      if (settled) return;
      settled = true;
      stream?.fail(err?.message ?? "DLA persistent server failed");
      reject(err);
    };
    const req = request(
      {
        host: persistentServerHost,
        port: persistentServerPort,
        path: "/v1/chat/completions",
        method: "POST",
        headers: {
          "content-type": "application/json; charset=utf-8",
          "content-length": Buffer.byteLength(payload),
        },
      },
      nativeRes => {
        const isSse = String(nativeRes.headers["content-type"] ?? "").includes("text/event-stream");
        const ok = (nativeRes.statusCode ?? 0) >= 200 && (nativeRes.statusCode ?? 0) < 300;
        if (ok) stream = beginAnthropicStream(res);
        nativeRes.setEncoding("utf8");
        nativeRes.setTimeout(persistentServerRequestTimeoutMs, () => {
          req.destroy(new Error("persistent_server_timeout"));
        });
        nativeRes.on("data", chunk => {
          output += chunk;
          if (isSse) {
            sseBuffer += chunk;
            const lines = sseBuffer.split(/\r?\n/);
            sseBuffer = lines.pop() ?? "";
            for (const line of lines) {
              const trimmed = line.trim();
              if (!trimmed.startsWith("data:")) continue;
              const data = trimmed.slice(5).trim();
              if (!data || data === "[DONE]") continue;
              try {
                const delta = openAiStreamDelta(JSON.parse(data));
                if (!delta) continue;
                if (!firstDeltaLogged) {
                  firstDeltaLogged = true;
                  perfLog(requestId, "persistent_first_delta", startedAt, `rawChars=${output.length}`);
                }
                streamed += delta;
                stream?.writeText(delta);
              } catch {
                // Ignore malformed SSE fragments; the non-stream fallback below handles empty output.
              }
            }
          }
        });
        nativeRes.on("end", () => {
          if (settled) return;
          settled = true;
          if ((nativeRes.statusCode ?? 0) < 200 || (nativeRes.statusCode ?? 0) >= 300) {
            const err = new Error(output.trim() || "DLA persistent server returned " + nativeRes.statusCode);
            err.code = "PERSISTENT_SERVER";
            return reject(err);
          }
          if (!isSse && !streamed.trim()) {
            try {
              streamed = parseOpenAiText(output);
              if (streamed) {
                perfLog(requestId, "persistent_first_delta", startedAt, `rawChars=${output.length} buffered=true`);
                stream?.writeText(streamed);
              }
            } catch (err) {
              stream?.fail(err?.message ?? "DLA persistent server parse failed");
              return reject(err);
            }
          }
          stream?.end();
          perfLog(requestId, "persistent_completed", startedAt, `streamedChars=${streamed.length}`);
          resolve(streamed.trim());
        });
      },
    );
    req.setTimeout(persistentServerRequestTimeoutMs, () => req.destroy(new Error("persistent_server_timeout")));
    req.on("error", fail);
    req.write(payload);
    req.end();
  });
}

async function callPersistentServer(prompt, maxTokens, res, requestId, startedAt, stream) {
  const ready = await ensurePersistentServer(requestId, startedAt);
  if (!ready) {
    const err = new Error("DLA persistent server unavailable");
    err.code = "PERSISTENT_UNAVAILABLE";
    throw err;
  }
  perfLog(requestId, "persistent_request_started", startedAt, `stream=${!!stream}`);
  if (stream) {
    const streamed = await callPersistentServerStream(prompt, maxTokens, res, requestId, startedAt);
    schedulePersistentServerIdleStop();
    return streamed;
  }
  const response = await httpJson(
    "/v1/chat/completions",
    persistentRequestBody(prompt, maxTokens, false),
    persistentServerRequestTimeoutMs,
  );
  if (response.statusCode < 200 || response.statusCode >= 300) {
    const err = new Error(response.body.trim() || "DLA persistent server returned " + response.statusCode);
    err.code = "PERSISTENT_SERVER";
    throw err;
  }
  const text = parseOpenAiText(response.body);
  perfLog(requestId, "persistent_completed", startedAt, `textChars=${text.length}`);
  schedulePersistentServerIdleStop();
  return text;
}

function workerErrorType(output) {
  if (/Permission denied/i.test(output) && /apusys|neuron|mdla|npu/i.test(output)) {
    return "qwen_dla_npu_permission";
  }
  if (/Cannot load network|Cannot create device|NeuronRuntime|Begin model init/i.test(output)) {
    return "qwen_dla_runtime";
  }
  return "qwen_dla_error";
}

function isDegenerateOutput(text) {
  const compact = (text ?? "").replace(/\s+/g, "");
  if (compact.length < 24) return false;
  return /(.{2,16})\1{5,}/.test(compact);
}

function runWorker(prompt, maxTokens, requestId, startedAt) {
  assertMemoryBudget();
  perfLog(requestId, "memory_checked", startedAt);
  const { dir, promptFile } = writeWorkerPrompt(prompt);
  perfLog(requestId, "prompt_file_written", startedAt, `promptChars=${prompt.length}`);
  return new Promise((resolve, reject) => {
    let output = "";
    let timedOut = false;
    const child = spawnWorker(promptFile, maxTokens);
    perfLog(requestId, "worker_spawned", startedAt, `pid=${child.pid ?? ""} maxTokens=${maxTokens}`);
    let firstStdoutLogged = false;
    if (workerPidFile) writeFileSync(workerPidFile, String(child.pid ?? ""), "utf8");
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 500).unref();
    }, timeoutMs);
    child.stdout.on("data", chunk => {
      if (!firstStdoutLogged) {
        firstStdoutLogged = true;
        perfLog(requestId, "first_stdout", startedAt);
      }
      output += chunk.toString();
    });
    child.stderr.on("data", chunk => {
      output += chunk.toString();
    });
    child.on("error", reject);
    child.on("close", code => {
      clearTimeout(timer);
      if (workerPidFile) writeFileSync(workerPidFile, "", "utf8");
      rmSync(dir, { recursive: true, force: true });
      if (timedOut) {
        perfLog(requestId, "worker_timeout", startedAt);
        const err = new Error("DLA worker timed out");
        err.code = "TIMEOUT";
        reject(err);
      } else if (code === 0) {
        const parsed = parseDlaOutput(output);
        perfLog(requestId, "worker_closed", startedAt, `code=${code} rawChars=${output.length} parsedChars=${parsed.length}`);
        resolve(parsed);
      } else {
        perfLog(requestId, "worker_failed", startedAt, `code=${code} rawChars=${output.length}`);
        const err = new Error(output.trim() || "DLA worker exited with " + code);
        err.code = workerErrorType(output);
        reject(err);
      }
    });
  });
}

function writeWorkerPrompt(prompt) {
  mkdirSync(promptDir, { recursive: true });
  const dir = mkdtempSync(join(promptDir, "openclaw-dla-"));
  const promptFile = join(dir, "prompt.txt");
  writeFileSync(promptFile, prompt.replace(/\r?\n/g, " ") + "\n", "utf8");
  return { dir, promptFile };
}

function spawnWorker(promptFile, maxTokens) {
  return spawn(
    mainBin,
    [config, "-i", promptFile, "--preformatter", preformatter, "-m", String(maxTokens), "--one-prompt-per-line"],
    {
      cwd: llmDir,
      env: { ...process.env, LD_LIBRARY_PATH: (process.env.LD_LIBRARY_PATH ?? "") + ":" + llmDir },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function streamChunks(text) {
  const chunks = [];
  const source = text || "";
  const size = Math.max(1, streamChunkChars);
  for (let index = 0; index < source.length; index += size) {
    chunks.push(source.slice(index, index + size));
  }
  return chunks.length ? chunks : [""];
}

async function writeAnthropicStream(res, text) {
  const stream = beginAnthropicStream(res);
  for (const chunk of streamChunks(text)) {
    stream.writeText(chunk);
    if (streamChunkDelayMs > 0) await sleep(streamChunkDelayMs);
  }
  stream.end();
}

function beginAnthropicStream(res) {
  res.writeHead(200, {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    "connection": "keep-alive",
  });
  res.flushHeaders?.();
  const id = "msg_" + Date.now();
  const writeEvent = (event, payload) => {
    res.write("event: " + event + "\n");
    res.write("data: " + JSON.stringify(payload) + "\n\n");
  };
  writeEvent(
    "message_start",
    {
      type: "message_start",
      message: {
        id,
        type: "message",
        role: "assistant",
        model: modelId,
        content: [],
        stop_reason: null,
        usage: { input_tokens: 0, output_tokens: 0 },
      },
    },
  );
  writeEvent(
    "content_block_start",
    {
      type: "content_block_start",
      index: 0,
      content_block: { type: "text", text: "" },
    },
  );

  return {
    writeText(text) {
      if (!text) return;
      writeEvent("content_block_delta", {
        type: "content_block_delta",
        index: 0,
        delta: { type: "text_delta", text },
      });
    },
    end() {
      writeEvent("content_block_stop", { type: "content_block_stop", index: 0 });
      writeEvent(
        "message_delta",
        {
          type: "message_delta",
          delta: { stop_reason: "end_turn" },
          usage: { output_tokens: 0 },
        },
      );
      writeEvent("message_stop", { type: "message_stop" });
      res.end();
    },
    fail(message) {
      writeEvent("error", {
        type: "error",
        error: { type: "qwen_dla_error", message },
      });
      res.end();
    },
  };
}

function findFirstMarker(text, markers) {
  let first = null;
  for (const marker of markers) {
    const index = text.indexOf(marker);
    if (index >= 0 && (first === null || index < first.index)) first = { index, marker };
  }
  return first;
}

function trailingMarkerPrefixLength(text, markers) {
  let length = 0;
  for (const marker of markers) {
    for (let size = 1; size < marker.length; size += 1) {
      if (text.endsWith(marker.slice(0, size))) length = Math.max(length, size);
    }
  }
  return length;
}

function extractLiveResponseDelta(state, chunk) {
  if (state.done) return "";
  const terminalMarkers = ["</eos>", "</end>", "\r\n[Full Response]", "\n[Full Response]", "[Full Response]"];
  let text = state.pending + chunk;
  state.pending = "";

  if (!state.started) {
    const match = text.match(/Response \[Max Length = \d+\]:\s*/);
    if (!match || match.index === undefined) {
      state.pending = text.slice(-128);
      return "";
    }
    state.started = true;
    text = text.slice(match.index + match[0].length);
  }

  const terminal = findFirstMarker(text, terminalMarkers);
  if (terminal) {
    state.done = true;
    text = text.slice(0, terminal.index);
  } else {
    const held = trailingMarkerPrefixLength(text, terminalMarkers);
    if (held > 0) {
      state.pending = text.slice(-held);
      text = text.slice(0, -held);
    }
  }

  return text.replaceAll("</eos>", "");
}

function streamWorker(prompt, maxTokens, res, requestId, startedAt) {
  assertMemoryBudget();
  perfLog(requestId, "memory_checked", startedAt);
  const { dir, promptFile } = writeWorkerPrompt(prompt);
  perfLog(requestId, "prompt_file_written", startedAt, `promptChars=${prompt.length}`);
  return new Promise((resolve, reject) => {
    let output = "";
    let streamed = "";
    let timedOut = false;
    let closedByClient = false;
    let settled = false;
    const state = { started: false, done: false, pending: "" };
    const child = spawnWorker(promptFile, maxTokens);
    const stream = beginAnthropicStream(res);
    perfLog(requestId, "worker_spawned", startedAt, `pid=${child.pid ?? ""} maxTokens=${maxTokens}`);
    let firstDeltaLogged = false;
    if (workerPidFile) writeFileSync(workerPidFile, String(child.pid ?? ""), "utf8");

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 500).unref();
    }, timeoutMs);
    const cleanup = () => {
      clearTimeout(timer);
      if (workerPidFile) writeFileSync(workerPidFile, "", "utf8");
      rmSync(dir, { recursive: true, force: true });
    };
    const finish = () => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(streamed.trim());
    };
    const fail = err => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    };

    res.on("close", () => {
      if (settled || res.writableEnded) return;
      closedByClient = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 500).unref();
    });
    child.stdout.on("data", chunk => {
      const text = chunk.toString();
      output += text;
      const delta = extractLiveResponseDelta(state, text);
      if (!delta) return;
      if (!firstDeltaLogged) {
        firstDeltaLogged = true;
        perfLog(requestId, "first_delta", startedAt, `rawChars=${output.length}`);
      }
      streamed += delta;
      stream.writeText(delta);
    });
    child.stderr.on("data", chunk => {
      output += chunk.toString();
    });
    child.on("error", err => {
      stream.fail(err?.message ?? "DLA worker failed");
      fail(err);
    });
    child.on("close", code => {
      if (closedByClient) return finish();
      if (timedOut) {
        perfLog(requestId, "worker_timeout", startedAt);
        stream.fail("DLA worker timed out");
        const err = new Error("DLA worker timed out");
        err.code = "TIMEOUT";
        return fail(err);
      }
      if (code !== 0) {
        perfLog(requestId, "worker_failed", startedAt, `code=${code} rawChars=${output.length} streamedChars=${streamed.length}`);
        const err = new Error(output.trim() || "DLA worker exited with " + code);
        err.code = workerErrorType(output);
        stream.fail(err.message);
        return fail(err);
      }
      if (!streamed.trim()) {
        streamed = parseDlaOutput(output);
        stream.writeText(streamed);
      }
      stream.end();
      perfLog(requestId, "worker_closed", startedAt, `code=${code} rawChars=${output.length} streamedChars=${streamed.length}`);
      finish();
    });
  });
}

async function handleGenerate(req, res, body, openAi) {
  if (active) return error(res, 429, "qwen_dla_busy", "Qwen DLA worker is already running");
  active = true;
  const startedAt = perfNow();
  const requestId = `${startedAt}-${Math.random().toString(16).slice(2, 8)}`;
  perfLog(requestId, "request_received", startedAt, `path=${req.url ?? ""} openAi=${openAi} stream=${!!body.stream}`);
  try {
    const maxTokens = Math.max(
      1,
      Math.min(Number(body.max_tokens ?? body.max_completion_tokens ?? defaultMaxTokens), defaultMaxTokens),
    );
    const prompt = openAi ? promptFromOpenAi(body) : promptFromAnthropic(body);
    perfLog(requestId, "prompt_built", startedAt, `promptChars=${prompt.length} maxTokens=${maxTokens}`);
    const wantsAnthropicStream = !!body.stream && !openAi;
    const canUsePersistent = persistentServerEnabled() && (!wantsAnthropicStream || persistentStreamEnabled());
    if (persistentServerEnabled() && wantsAnthropicStream && !persistentStreamEnabled()) {
      perfLog(requestId, "persistent_stream_skipped", startedAt, "reason=disabled_for_true_stream");
    }
    if (canUsePersistent) {
      try {
        const persistentText = await callPersistentServer(prompt, maxTokens, res, requestId, startedAt, wantsAnthropicStream);
        if (wantsAnthropicStream) {
          perfLog(requestId, "request_completed", startedAt, `mode=persistent_stream streamedChars=${persistentText.length}`);
          return;
        }
        if (openAi) {
          perfLog(requestId, "response_ready", startedAt, `mode=persistent_openai textChars=${persistentText.length}`);
          return json(res, 200, {
            id: "chatcmpl-" + Date.now(),
            object: "chat.completion",
            created: Math.floor(Date.now() / 1000),
            model: modelId,
            choices: [{ index: 0, message: { role: "assistant", content: persistentText }, finish_reason: "stop" }],
            usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
          });
        }
        perfLog(requestId, "response_ready", startedAt, `mode=persistent_anthropic textChars=${persistentText.length}`);
        return json(res, 200, {
          id: "msg_" + Date.now(),
          type: "message",
          role: "assistant",
          model: modelId,
          content: [{ type: "text", text: persistentText }],
          stop_reason: "end_turn",
          usage: { input_tokens: 0, output_tokens: 0 },
        });
      } catch (err) {
        perfLog(requestId, "persistent_failed", startedAt, `fallback=short_lived_worker code=${err?.code ?? ""} message=${String(err?.message ?? "").slice(0, 120)}`);
        stopPersistentServer("fallback");
        if (res.headersSent || res.writableEnded) return;
      }
    }
    if (wantsAnthropicStream) {
      const streamedText = await streamWorker(prompt, maxTokens, res, requestId, startedAt);
      perfLog(requestId, "request_completed", startedAt, `mode=stream streamedChars=${streamedText.length}`);
      return;
    }
    const text = await runWorker(prompt, maxTokens, requestId, startedAt);
    if (isDegenerateOutput(text)) {
      const err = new Error("DLA worker produced repetitive output");
      err.code = "DEGENERATE_OUTPUT";
      throw err;
    }
    if (openAi) {
      perfLog(requestId, "response_ready", startedAt, `mode=openai textChars=${text.length}`);
      return json(res, 200, {
        id: "chatcmpl-" + Date.now(),
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: modelId,
        choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      });
    }
    perfLog(requestId, "response_ready", startedAt, `mode=anthropic textChars=${text.length}`);
    json(res, 200, {
      id: "msg_" + Date.now(),
      type: "message",
      role: "assistant",
      model: modelId,
      content: [{ type: "text", text }],
      stop_reason: "end_turn",
      usage: { input_tokens: 0, output_tokens: 0 },
    });
  } catch (err) {
    perfLog(requestId, "request_failed", startedAt, `code=${err?.code ?? ""} message=${String(err?.message ?? "").slice(0, 120)}`);
    if (res.headersSent || res.writableEnded) return;
    const code =
      err?.code === "LOW_MEMORY" ? 503 :
        err?.code === "TIMEOUT" ? 504 :
          err?.code === "CONTEXT_OVERFLOW" ? 413 :
            err?.code === "DEGENERATE_OUTPUT" ? 502 :
              500;
    error(
      res,
      code,
      err?.code === "LOW_MEMORY"
        ? "qwen_dla_low_memory"
        : err?.code === "TIMEOUT"
          ? "qwen_dla_timeout"
          : err?.code === "CONTEXT_OVERFLOW"
            ? "qwen_dla_context_overflow"
            : err?.code === "DEGENERATE_OUTPUT"
              ? "qwen_dla_degenerate_output"
              : err?.code ?? "qwen_dla_error",
      err?.message ?? "DLA worker failed",
    );
  } finally {
    active = false;
  }
}

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://" + host + ":" + port);
  const path = url.pathname.replace(/^\/v1\/v1(?=\/)/, "/v1");
  if (req.method === "GET" && (path === "/models" || path === "/v1/models")) {
    return json(res, 200, { object: "list", data: [{ id: modelId, object: "model" }] });
  }
  if (req.method !== "POST") return error(res, 404, "not_found", "Not found");
  try {
    const body = JSON.parse(await readBody(req) || "{}");
    if (path === "/messages" || path === "/v1/messages") return handleGenerate(req, res, body, false);
    if (path === "/chat/completions" || path === "/v1/chat/completions") return handleGenerate(req, res, body, true);
    return error(res, 404, "not_found", "Not found");
  } catch (err) {
    return error(res, 400, "bad_request", err?.message ?? "Bad request");
  }
}).listen(port, host, () => {
  console.log("OpenClaw DLA bridge listening on " + host + ":" + port);
});

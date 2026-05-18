package ai.openclaw.app.node

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

class LocalDlaBridgeLauncher(
  context: Context? = null,
  private val runner: CommandRunner = ProcessCommandRunner(),
  private val nativeLibraryDir: String? = context?.applicationContext?.applicationInfo?.nativeLibraryDir,
  private val canConnectProbe: (suspend (String, Int) -> Boolean)? = null,
) {
  private val appContext = context?.applicationContext
  private val appHome =
    appContext
      ?.filesDir
      ?.resolve("openclaw")
      ?.resolve("home")
      ?.absolutePath
  private val appRoot = appContext?.filesDir?.resolve("openclaw")
  private val appPrefix = appRoot?.resolve("usr")?.absolutePath
  private val bundledNativeLibDir = appRoot?.resolve("native-libs/arm64-v8a")?.absolutePath
  private val packagedNode =
    nativeLibraryDir
      ?.trimEnd('/')
      ?.let { nativeDir ->
        listOf(
          "$nativeDir/${LocalGatewayLauncher.PACKAGED_NODE_LIBRARY}",
          "$nativeDir/arm64/${LocalGatewayLauncher.PACKAGED_NODE_LIBRARY}",
        )
      }.orEmpty()
  private val packagedDlaMain =
    nativeLibraryDir
      ?.trimEnd('/')
      ?.let { nativeDir ->
        listOf(
          "$nativeDir/$PACKAGED_DLA_MAIN_LIBRARY",
          "$nativeDir/arm64/$PACKAGED_DLA_MAIN_LIBRARY",
        )
      }.orEmpty()
  private val packagedDlaServer =
    nativeLibraryDir
      ?.trimEnd('/')
      ?.let { nativeDir ->
        listOf(
          "$nativeDir/$PACKAGED_DLA_SERVER_LIBRARY",
          "$nativeDir/arm64/$PACKAGED_DLA_SERVER_LIBRARY",
        )
      }.orEmpty()
  private val lock = Mutex()

  suspend fun ensureStarted(): LocalDlaBridgeLaunchResult =
    lock.withLock {
      val bridgeScriptChanged = runCatching { stageBridgeScript() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
      if (canConnect(HOST, PORT)) {
        if (!bridgeScriptChanged && isCurrentBridgeReachable()) return LocalDlaBridgeLaunchResult.AlreadyRunning
        runner.run(listOf("/system/bin/sh", "-c", buildStopScript()), timeoutMs = 8_000)
        repeat(10) {
          if (!canConnect(HOST, PORT)) return@repeat
          delay(100)
        }
      }
      runCatching { stageBundledNativeLibraries() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
      if (!hasPackagedDlaMainExecutable()) {
        runCatching { stageDlaMainExecutable() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
        runCatching { stageDlaRuntimeLibraries() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
      }
      if (!hasPackagedDlaServerExecutable()) {
        runCatching { stageDlaServerExecutable() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
        runCatching { stageDlaRuntimeLibraries() }.getOrElse { return LocalDlaBridgeLaunchResult.Failed }
      }
      val result =
        runCatching {
          runner.run(listOf("/system/bin/sh", "-c", buildStartScript()), timeoutMs = 10_000)
        }.getOrElse {
          return LocalDlaBridgeLaunchResult.Failed
        }
      if (!result.success) return LocalDlaBridgeLaunchResult.Unavailable
      if (waitUntilReachable()) LocalDlaBridgeLaunchResult.Started else LocalDlaBridgeLaunchResult.Failed
    }

  suspend fun stop(): LocalDlaBridgeStopResult =
    lock.withLock {
      val result = runner.run(listOf("/system/bin/sh", "-c", buildStopScript()), timeoutMs = 8_000)
      if (result.success) LocalDlaBridgeStopResult.Stopped else LocalDlaBridgeStopResult.Failed
    }

  suspend fun warmupPersistentServer(generate: Boolean = false): LocalDlaBridgeWarmupResult =
    withContext(Dispatchers.IO) {
      runCatching {
        val url = URL("http://$HOST:$PORT/v1/warmup")
        val payload = """{"generate":$generate}"""
        val connection = (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = WARMUP_CONNECT_TIMEOUT_MS
          readTimeout = WARMUP_READ_TIMEOUT_MS
          doOutput = true
          setRequestProperty("content-type", "application/json; charset=utf-8")
        }
        try {
          connection.outputStream.use { output ->
            output.write(payload.toByteArray(Charsets.UTF_8))
          }
          val code = connection.responseCode
          when (code) {
            in 200..299 -> LocalDlaBridgeWarmupResult.Ready
            503 -> LocalDlaBridgeWarmupResult.Unavailable
            else -> LocalDlaBridgeWarmupResult.Failed
          }
        } finally {
          connection.disconnect()
        }
      }.getOrElse {
        LocalDlaBridgeWarmupResult.Failed
      }
    }

  internal fun buildStartScript(): String =
    buildScript(
      "APP_HOME=\"${appHome.orEmpty()}\"",
      "APP_PREFIX=\"${appPrefix.orEmpty()}\"",
      "NATIVE_LIBRARY_DIR=\"${nativeLibraryDir.orEmpty()}\"",
      "BUNDLED_NATIVE_LIB_DIR=\"${bundledNativeLibDir.orEmpty()}\"",
      "LLM_DIR=\"$DEFAULT_LLM_DIR\"",
      "CONFIG=\"$DEFAULT_CONFIG\"",
      "BRIDGE_PORT=\"$PORT\"",
      "if [ -z \"\$APP_HOME\" ]; then",
      "  exit 127",
      "fi",
      "if [ ! -r \"\$LLM_DIR/main\" ] || [ ! -f \"\$LLM_DIR/\$CONFIG\" ]; then",
      "  exit 126",
      "fi",
      "mkdir -p \"\$APP_HOME/.openclaw\"",
      "BRIDGE_SCRIPT=\"\$APP_HOME/.openclaw/android-dla-bridge.mjs\"",
      "DLA_MAIN_BIN=\"\$APP_HOME/.openclaw/android-dla-main\"",
      "DLA_SERVER_BIN=\"\$APP_HOME/.openclaw/android-dla-server\"",
      "for candidate in ${packagedDlaMainCandidates()}; do",
      "  if [ -x \"\$candidate\" ]; then",
      "    DLA_MAIN_BIN=\"\$candidate\"",
      "    break",
      "  fi",
      "done",
      "for candidate in ${packagedDlaServerCandidates()}; do",
      "  if [ -x \"\$candidate\" ]; then",
      "    DLA_SERVER_BIN=\"\$candidate\"",
      "    break",
      "  fi",
      "done",
      "DLA_LIB_DIR=\"\$APP_HOME/.openclaw/dla-libs\"",
      "PROMPT_DIR=\"\$APP_HOME/.openclaw/dla-prompts\"",
      "PID_FILE=\"\$APP_HOME/.openclaw/android-dla-bridge.pid\"",
      "LOG_FILE=\"\$APP_HOME/.openclaw/android-dla-bridge.log\"",
      "WORKER_PID_FILE=\"\$APP_HOME/.openclaw/android-dla-worker.pid\"",
      "SERVER_PID_FILE=\"\$APP_HOME/.openclaw/android-dla-server.pid\"",
      "if [ ! -f \"\$BRIDGE_SCRIPT\" ]; then",
      "  exit 126",
      "fi",
      "if [ ! -x \"\$DLA_MAIN_BIN\" ]; then",
      "  exit 126",
      "fi",
      "if [ ! -x \"\$DLA_SERVER_BIN\" ]; then",
      "  exit 126",
      "fi",
      "mkdir -p \"\$PROMPT_DIR\"",
      "if [ -n \"\$APP_PREFIX\" ]; then",
      "  export PREFIX=\"\$APP_PREFIX\"",
      "  mkdir -p \"\$PREFIX/tmp\"",
      "  export TMPDIR=\"\$PREFIX/tmp\"",
      "  export TMP=\"\$TMPDIR\"",
      "  export TEMP=\"\$TMPDIR\"",
      "fi",
      "if [ -n \"\$NATIVE_LIBRARY_DIR\" ]; then",
      "  export LD_LIBRARY_PATH=\"\$DLA_LIB_DIR:\$BUNDLED_NATIVE_LIB_DIR:\$NATIVE_LIBRARY_DIR:\${PREFIX:-}/lib\"",
      "else",
      "  export LD_LIBRARY_PATH=\"\$DLA_LIB_DIR:\$BUNDLED_NATIVE_LIB_DIR:\${PREFIX:-}/lib\"",
      "fi",
      "export PATH=\"\${PREFIX:-}/bin:/system/bin:/system/xbin\"",
      "old=\"\$(cat \"\$PID_FILE\" 2>/dev/null || true)\"",
      "if [ -n \"\$old\" ] && kill -0 \"\$old\" 2>/dev/null; then",
      "  exit 0",
      "fi",
      "PACKAGED_NODE=\"\"",
      "for candidate in ${packagedNodeCandidates()}; do",
      "  if [ -x \"\$candidate\" ]; then",
      "    PACKAGED_NODE=\"\$candidate\"",
      "    break",
      "  fi",
      "done",
      "if [ -n \"\$PACKAGED_NODE\" ]; then",
      "  NODE_BIN=\"\$PACKAGED_NODE\"",
      "elif [ -n \"\$PREFIX\" ] && [ -x \"\$PREFIX/bin/node\" ]; then",
      "  NODE_BIN=\"\$PREFIX/bin/node\"",
      "elif command -v node >/dev/null 2>&1; then",
      "  NODE_BIN=\"\$(command -v node)\"",
      "else",
      "  exit 127",
      "fi",
      "export OPENCLAW_DLA_HOST=\"$HOST\"",
      "export OPENCLAW_DLA_PORT=\"\$BRIDGE_PORT\"",
      "export OPENCLAW_DLA_LLM_DIR=\"\$LLM_DIR\"",
      "export OPENCLAW_DLA_MAIN_BIN=\"\$DLA_MAIN_BIN\"",
      "export OPENCLAW_DLA_PROMPT_DIR=\"\$PROMPT_DIR\"",
      "export OPENCLAW_DLA_CONFIG=\"\$CONFIG\"",
      "export OPENCLAW_DLA_MODEL_ID=\"${LocalModelProviderConfig.DLA_MODEL_ID}\"",
      "export OPENCLAW_DLA_PREFORMATTER=\"$DEFAULT_PREFORMATTER\"",
      "export OPENCLAW_DLA_MAX_TOKENS=\"$DEFAULT_MAX_TOKENS\"",
      "export OPENCLAW_DLA_MIN_AVAILABLE_KB=\"$MIN_AVAILABLE_KB\"",
      "export OPENCLAW_DLA_APP_PSS_LIMIT_KB=\"$APP_PSS_LIMIT_KB\"",
      "export OPENCLAW_DLA_TIMEOUT_MS=\"$REQUEST_TIMEOUT_MS\"",
      "export OPENCLAW_DLA_WORKER_PID_FILE=\"\$WORKER_PID_FILE\"",
      "export OPENCLAW_DLA_PERSISTENT_MODE=\"auto\"",
      "export OPENCLAW_DLA_PERSISTENT_STREAM=\"true\"",
      "export OPENCLAW_DLA_SERVER_BIN=\"\$DLA_SERVER_BIN\"",
      "export OPENCLAW_DLA_SERVER_HOST=\"$HOST\"",
      "export OPENCLAW_DLA_SERVER_PORT=\"$PERSISTENT_SERVER_PORT\"",
      "export OPENCLAW_DLA_SERVER_PID_FILE=\"\$SERVER_PID_FILE\"",
      "export OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS=\"$PERSISTENT_SERVER_IDLE_TIMEOUT_MS\"",
      "export OPENCLAW_DLA_SERVER_REQUEST_TIMEOUT_MS=\"$PERSISTENT_SERVER_REQUEST_TIMEOUT_MS\"",
      "export OPENCLAW_DLA_PREWARM=\"true\"",
      "export OPENCLAW_DLA_PREWARM_GENERATE=\"false\"",
      "export OPENCLAW_ANDROID_PACKAGE=\"io.github.openclawcn.app\"",
      "nohup \"\$NODE_BIN\" \"\$BRIDGE_SCRIPT\" >\"\$LOG_FILE\" 2>&1 &",
      "echo \$! >\"\$PID_FILE\"",
      "exit 0",
    )

  internal fun buildStopScript(): String =
    buildScript(
      "APP_HOME=\"${appHome.orEmpty()}\"",
      "if [ -z \"\$APP_HOME\" ]; then",
      "  exit 0",
      "fi",
      "PID_FILE=\"\$APP_HOME/.openclaw/android-dla-bridge.pid\"",
      "WORKER_PID_FILE=\"\$APP_HOME/.openclaw/android-dla-worker.pid\"",
      "SERVER_PID_FILE=\"\$APP_HOME/.openclaw/android-dla-server.pid\"",
      "kill_pid() {",
      "  target=\"\$1\"",
      "  if [ -z \"\$target\" ]; then",
      "    return 0",
      "  fi",
      "  kill \"\$target\" 2>/dev/null || true",
      "  sleep 0.2",
      "  kill -9 \"\$target\" 2>/dev/null || true",
      "  if kill -0 \"\$target\" 2>/dev/null && command -v su >/dev/null 2>&1; then",
      "    su -c \"kill \$target 2>/dev/null || true; sleep 0.2; kill -9 \$target 2>/dev/null || true\" 2>/dev/null || true",
      "  fi",
      "}",
      "for file in \"\$WORKER_PID_FILE\" \"\$SERVER_PID_FILE\" \"\$PID_FILE\"; do",
      "  old=\"\$(cat \"\$file\" 2>/dev/null || true)\"",
      "  kill_pid \"\$old\"",
      "  rm -f \"\$file\"",
      "done",
      "pkill -f android-dla-bridge.mjs 2>/dev/null || true",
      "if command -v su >/dev/null 2>&1; then",
      "  su -c \"pkill -f android-dla-bridge.mjs 2>/dev/null || true\" 2>/dev/null || true",
      "fi",
      "exit 0",
    )

  private suspend fun stageBridgeScript(): Boolean {
    val context = appContext ?: return false
    val targetHome = appHome?.let(::File) ?: return false
    return withContext(Dispatchers.IO) {
      val target = targetHome.resolve(".openclaw/android-dla-bridge.mjs")
      target.parentFile?.mkdirs()
      val stagedBytes = context.assets.open(BRIDGE_ASSET_PATH).use { input -> input.readBytes() }
      val changed = !target.exists() || !target.readBytes().contentEquals(stagedBytes)
      if (changed) {
        target.outputStream().use { output -> output.write(stagedBytes) }
      }
      target.setReadable(true, true)
      changed
    }
  }

  private suspend fun stageBundledNativeLibraries() {
    val context = appContext ?: return
    val targetDir = appRoot?.resolve("native-libs/arm64-v8a") ?: return
    withContext(Dispatchers.IO) {
      targetDir.mkdirs()
      LocalGatewayLauncher.BUNDLED_NATIVE_LIBS_FOR_TEST.forEach { name ->
        val target = targetDir.resolve(name)
        context.assets.open("$BUNDLED_NATIVE_LIB_ASSET_DIR/$name").use { input ->
          if (target.exists() && target.length() == input.available().toLong()) {
            return@forEach
          }
          target.outputStream().use { output -> input.copyTo(output) }
          target.setReadable(true, true)
        }
      }
    }
  }

  private suspend fun stageDlaMainExecutable() {
    val targetHome = appHome?.let(::File) ?: return
    withContext(Dispatchers.IO) {
      val source = File(DEFAULT_LLM_DIR, "main")
      if (!source.canRead()) {
        error("DLA main is not readable")
      }
      val target = targetHome.resolve(".openclaw/android-dla-main")
      target.parentFile?.mkdirs()
      if (!target.exists() || target.length() != source.length()) {
        source.inputStream().use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
        }
      }
      target.setReadable(true, true)
      target.setExecutable(true, true)
    }
  }

  private suspend fun stageDlaServerExecutable() {
    val targetHome = appHome?.let(::File) ?: return
    withContext(Dispatchers.IO) {
      val source = File(DEFAULT_LLM_DIR, DEFAULT_SERVER_BIN_NAME)
      if (!source.canRead()) {
        error("DLA server is not readable")
      }
      val target = targetHome.resolve(".openclaw/android-dla-server")
      target.parentFile?.mkdirs()
      if (!target.exists() || target.length() != source.length()) {
        source.inputStream().use { input ->
          target.outputStream().use { output -> input.copyTo(output) }
        }
      }
      target.setReadable(true, true)
      target.setExecutable(true, true)
    }
  }

  private suspend fun stageDlaRuntimeLibraries() {
    val targetHome = appHome?.let(::File) ?: return
    withContext(Dispatchers.IO) {
      val sourceDir = File(DEFAULT_LLM_DIR)
      val targetDir = targetHome.resolve(".openclaw/dla-libs")
      targetDir.mkdirs()
      sourceDir
        .listFiles { file -> file.isFile && file.name.contains(".so") }
        .orEmpty()
        .forEach { source ->
          val target = targetDir.resolve(source.name)
          if (!target.exists() || target.length() != source.length()) {
            source.inputStream().use { input ->
              target.outputStream().use { output -> input.copyTo(output) }
            }
          }
          target.setReadable(true, true)
          target.setExecutable(true, true)
        }
    }
  }

  private suspend fun waitUntilReachable(): Boolean {
    repeat(20) {
      if (canConnect(HOST, PORT)) return true
      delay(250)
    }
    return false
  }

  private suspend fun isCurrentBridgeReachable(): Boolean {
    if (canConnectProbe != null) return true
    return withContext(Dispatchers.IO) {
      runCatching {
        val connection = (URL("http://$HOST:$PORT/v1/health").openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 800
          readTimeout = 800
        }
        try {
          connection.responseCode in 200..299 &&
            connection.inputStream.bufferedReader().use { it.readText() }.contains(BRIDGE_FEATURE_VERSION)
        } finally {
          connection.disconnect()
        }
      }.getOrDefault(false)
    }
  }

  private suspend fun canConnect(
    host: String,
    port: Int,
  ): Boolean =
    canConnectProbe?.invoke(host, port) ?: withContext(Dispatchers.IO) {
      runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(host, port), 400)
        }
      }.isSuccess
    }

  private fun buildScript(vararg lines: String): String = lines.joinToString(separator = "\n")

  private fun packagedNodeCandidates(): String = packagedNode.joinToString(" ") { "\"$it\"" }.ifBlank { "\"\"" }

  private fun packagedDlaMainCandidates(): String = packagedDlaMain.joinToString(" ") { "\"$it\"" }.ifBlank { "\"\"" }

  private fun packagedDlaServerCandidates(): String = packagedDlaServer.joinToString(" ") { "\"$it\"" }.ifBlank { "\"\"" }

  private fun hasPackagedDlaMainExecutable(): Boolean = packagedDlaMain.any { File(it).canExecute() }

  private fun hasPackagedDlaServerExecutable(): Boolean = packagedDlaServer.any { File(it).canExecute() }

  companion object {
    const val BRIDGE_ASSET_PATH = "openclaw/dla/android-dla-bridge.mjs"
    const val HOST = "127.0.0.1"
    const val PORT = 8081
    const val DEFAULT_LLM_DIR = "/data/local/tmp/llm_sdk"
    const val DEFAULT_CONFIG = "config_np8-qwen3-1.7b.yaml"
    const val DEFAULT_PREFORMATTER = "Qwen3NoInputNoThink"
    const val BRIDGE_FEATURE_VERSION = "product-kb-direct-v1"
    const val DEFAULT_MAX_TOKENS = 384
    const val MIN_AVAILABLE_KB = 1_250_000
    const val APP_PSS_LIMIT_KB = 2_200_000
    const val REQUEST_TIMEOUT_MS = 120_000
    const val PERSISTENT_SERVER_PORT = 18082
    const val PERSISTENT_SERVER_IDLE_TIMEOUT_MS = 600_000
    const val PERSISTENT_SERVER_REQUEST_TIMEOUT_MS = 30_000
    private const val WARMUP_CONNECT_TIMEOUT_MS = 1_500
    private const val WARMUP_READ_TIMEOUT_MS = 45_000
    private const val BUNDLED_NATIVE_LIB_ASSET_DIR = "openclaw/native-libs/arm64-v8a"
    private const val DEFAULT_SERVER_BIN_NAME = "openclaw-qwen-dla-server"
    const val PACKAGED_DLA_MAIN_LIBRARY = "libopenclaw_dla_main.so"
    const val PACKAGED_DLA_SERVER_LIBRARY = "libopenclaw_dla_server.so"
  }
}

enum class LocalDlaBridgeLaunchResult {
  Unavailable,
  AlreadyRunning,
  Started,
  Failed,
}

enum class LocalDlaBridgeStopResult {
  Stopped,
  Failed,
}

enum class LocalDlaBridgeWarmupResult {
  Ready,
  Unavailable,
  Failed,
}

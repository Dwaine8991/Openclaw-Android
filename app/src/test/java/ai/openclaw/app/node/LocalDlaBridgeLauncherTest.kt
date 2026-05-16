package ai.openclaw.app.node

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalDlaBridgeLauncherTest {
  @Test
  fun buildStartScript_launchesStagedNodeBridgeWithGuardConfiguration() {
    val script =
      LocalDlaBridgeLauncher(
        runner = FakeCommandRunner,
        nativeLibraryDir = "/data/app/lib/arm64",
      ).buildStartScript()

    assertTrue(script.contains("android-dla-bridge.mjs"))
    assertTrue(script.contains("config_np8-qwen3-1.7b.yaml"))
    assertTrue(script.contains("OPENCLAW_DLA_MAX_TOKENS=\"384\""))
    assertTrue(script.contains("OPENCLAW_DLA_MIN_AVAILABLE_KB"))
    assertTrue(script.contains("OPENCLAW_DLA_APP_PSS_LIMIT_KB"))
    assertTrue(script.contains("OPENCLAW_DLA_WORKER_PID_FILE"))
    assertTrue(script.contains("OPENCLAW_DLA_PERSISTENT_MODE=\"auto\""))
    assertTrue(script.contains("OPENCLAW_DLA_PERSISTENT_STREAM=\"true\""))
    assertTrue(script.contains("DLA_SERVER_BIN=\"\$APP_HOME/.openclaw/android-dla-server\""))
    assertTrue(script.contains("OPENCLAW_DLA_SERVER_BIN=\"\$DLA_SERVER_BIN\""))
    assertTrue(script.contains("OPENCLAW_DLA_SERVER_PORT=\"18082\""))
    assertTrue(script.contains("OPENCLAW_DLA_SERVER_PID_FILE"))
    assertTrue(script.contains("OPENCLAW_DLA_SERVER_IDLE_TIMEOUT_MS=\"600000\""))
    assertTrue(script.contains("OPENCLAW_DLA_PREWARM=\"true\""))
    assertTrue(script.contains("OPENCLAW_DLA_PREWARM_GENERATE=\"false\""))
    assertTrue(script.contains("\"/data/app/lib/arm64/libopenclaw_node.so\""))
    assertTrue(script.contains("\"/data/app/lib/arm64/libopenclaw_dla_main.so\""))
    assertTrue(script.contains("\"/data/app/lib/arm64/libopenclaw_dla_server.so\""))
  }

  @Test
  fun bridgeAsset_spawnsShortLivedWorkerWithBusyLowMemoryAndTimeoutGuards() {
    val asset = File("src/main/assets/${LocalDlaBridgeLauncher.BRIDGE_ASSET_PATH}").readText()

    assertTrue(asset.contains("createServer"))
    assertTrue(asset.contains("qwen_dla_busy"))
    assertTrue(asset.contains("LOW_MEMORY"))
    assertTrue(asset.contains("qwen_dla_runtime"))
    assertTrue(asset.contains("qwen_dla_npu_permission"))
    assertTrue(asset.contains("qwen_dla_context_overflow"))
    assertTrue(asset.contains("qwen_dla_degenerate_output"))
    assertTrue(asset.contains("DLA_SALES_SYSTEM_PROMPT"))
    assertTrue(asset.contains("OPENCLAW_DLA_STREAM_CHUNK_CHARS"))
    assertTrue(asset.contains("content_block_delta"))
    assertTrue(asset.contains("spawn("))
    assertTrue(asset.contains("mainBin"))
    assertTrue(asset.contains("--one-prompt-per-line"))
    assertTrue(asset.contains("timeoutMs"))
    assertTrue(asset.contains("OPENCLAW_DLA_PERSISTENT_MODE"))
    assertTrue(asset.contains("OPENCLAW_DLA_PERSISTENT_STREAM"))
    assertTrue(asset.contains("ensurePersistentServer"))
    assertTrue(asset.contains("callPersistentServer"))
    assertTrue(asset.contains("warmPersistentServer"))
    assertTrue(asset.contains("warmup_start"))
    assertTrue(asset.contains("OPENCLAW_DLA_PREWARM"))
    assertTrue(asset.contains("path === \"/warmup\" || path === \"/v1/warmup\""))
    assertTrue(asset.contains("persistentStreamEnabled"))
    assertTrue(asset.contains("persistent_stream_skipped"))
    assertTrue(asset.contains("fallback=short_lived_worker"))
    assertTrue(asset.contains("dynamicMaxTokensForPrompt"))
    assertTrue(asset.contains("requestedMaxTokens"))
    assertTrue(asset.contains("native_response_headers"))
    assertTrue(asset.contains("persistent_first_byte"))
  }

  @Test
  fun bridgeAsset_streamRequestsUseLiveWorkerStdoutInsteadOfBufferedCompletion() {
    val asset = File("src/main/assets/${LocalDlaBridgeLauncher.BRIDGE_ASSET_PATH}").readText()

    assertTrue(asset.contains("streamWorker("))
    assertTrue(asset.contains("extractLiveResponseDelta("))
    assertTrue(asset.contains("await streamWorker(prompt, maxTokens, res, requestId, startedAt)"))
    assertTrue(
      asset.indexOf("await streamWorker(prompt, maxTokens, res, requestId, startedAt)") <
        asset.indexOf("const text = await runWorker(prompt, maxTokens, requestId, startedAt)"),
    )
  }

  @Test
  fun manifest_declaresMtkNativeLibrariesUsedByDlaRuntime() {
    val manifest = File("src/main/AndroidManifest.xml").readText()

    assertTrue(manifest.contains("libapuwareutils_v2.mtk.so"))
    assertTrue(manifest.contains("libapuwareapusys_v2.mtk.so"))
    assertTrue(manifest.contains("libnir_neon_driver_ndk.mtk.so"))
    assertTrue(manifest.contains("libnir_neon_driver_ndk.mtk.vndk.so"))
    assertTrue(manifest.contains("libcmdl_ndk.mtk.vndk.so"))
    assertTrue(manifest.contains("libcmdl_ndk.mtk.so"))
  }

  @Test
  fun buildStopScript_stopsBridgeAndCurrentWorker() {
    val script = LocalDlaBridgeLauncher(runner = FakeCommandRunner).buildStopScript()

    assertTrue(script.contains("android-dla-bridge.pid"))
    assertTrue(script.contains("android-dla-worker.pid"))
    assertTrue(script.contains("android-dla-server.pid"))
    assertTrue(script.contains("kill -9"))
  }

  @Test
  fun ensureStarted_skipsWhenBridgePortAlreadyReachable() =
    runTest {
      val runner = RecordingCommandRunner()
      val launcher =
        LocalDlaBridgeLauncher(
          runner = runner,
          canConnectProbe = { _, _ -> true },
        )

      val result = launcher.ensureStarted()

      assertEquals(LocalDlaBridgeLaunchResult.AlreadyRunning, result)
      assertTrue(runner.commands.isEmpty())
    }

  private object FakeCommandRunner : CommandRunner {
    override suspend fun run(
      command: List<String>,
      timeoutMs: Long,
    ): CommandRunResult = CommandRunResult(exitCode = 0)
  }

  private class RecordingCommandRunner : CommandRunner {
    val commands = mutableListOf<List<String>>()

    override suspend fun run(
      command: List<String>,
      timeoutMs: Long,
    ): CommandRunResult {
      commands.add(command)
      return CommandRunResult(exitCode = 0)
    }
  }
}

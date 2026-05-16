package ai.openclaw.app

import ai.openclaw.app.node.LocalModelProviderConfig
import ai.openclaw.app.voice.VoiceTtsOutputMode
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class SecurePrefsTest {
  @Test
  fun defaultsManualGatewayToLocalDeviceGateway() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()

    val prefs = SecurePrefs(context)

    assertTrue(prefs.manualEnabled.value)
    assertEquals("127.0.0.1", prefs.manualHost.value)
    assertEquals(SecurePrefs.defaultManualGatewayPort, prefs.manualPort.value)
    assertFalse(prefs.manualTls.value)
  }

  @Test
  fun loadLocationMode_migratesLegacyAlwaysValue() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putString("location.enabledMode", "always")
      .commit()

    val prefs = SecurePrefs(context)

    assertEquals(LocationMode.WhileUsing, prefs.locationMode.value)
    assertEquals("whileUsing", plainPrefs.getString("location.enabledMode", null))
  }

  @Test
  fun voiceMicEnabled_ignoresOldTalkEnabledKey() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putBoolean("talk.enabled", true)
      .commit()

    val prefs = SecurePrefs(context)

    assertFalse(prefs.voiceMicEnabled.value)
    assertFalse(plainPrefs.contains("voice.micEnabled"))
  }

  @Test
  fun setVoiceMicEnabled_persistsNewKeyOnly() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putBoolean("talk.enabled", false)
      .commit()
    val prefs = SecurePrefs(context)

    prefs.setVoiceMicEnabled(true)

    assertTrue(prefs.voiceMicEnabled.value)
    assertTrue(plainPrefs.getBoolean("voice.micEnabled", false))
    assertFalse(plainPrefs.getBoolean("talk.enabled", false))
  }

  @Test
  fun voiceTtsOutputMode_persistsSelectedRoute() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = SecurePrefs(context)

    assertEquals(VoiceTtsOutputMode.BuiltInSpeaker, prefs.voiceTtsOutputMode.value)

    prefs.setVoiceTtsOutputMode(VoiceTtsOutputMode.Q5Usb)

    assertEquals(VoiceTtsOutputMode.Q5Usb, prefs.voiceTtsOutputMode.value)
    assertEquals("q5_usb", plainPrefs.getString("voice.tts.outputMode", null))
  }

  @Test
  fun saveGatewayBootstrapToken_persistsSeparatelyFromSharedToken() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setGatewayToken("shared-token")
    prefs.setGatewayBootstrapToken("bootstrap-token")

    assertEquals("shared-token", prefs.loadGatewayToken())
    assertEquals("bootstrap-token", prefs.loadGatewayBootstrapToken())
    assertEquals("bootstrap-token", prefs.gatewayBootstrapToken.value)
  }

  @Test
  fun clearGatewaySetupAuth_removesStoredGatewayAuth() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test.clear", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setGatewayToken("shared-token")
    prefs.setGatewayBootstrapToken("bootstrap-token")
    prefs.setGatewayPassword("password-token")

    prefs.clearGatewaySetupAuth()

    assertEquals("", prefs.gatewayToken.value)
    assertEquals("", prefs.gatewayBootstrapToken.value)
    assertNull(prefs.loadGatewayToken())
    assertNull(prefs.loadGatewayBootstrapToken())
    assertNull(prefs.loadGatewayPassword())
  }

  @Test
  fun localModelProviderConfig_persistsKeySeparatelyFromModelMetadata() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test.local-model", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setLocalModelProviderConfig(
      baseUrl = " https://example.test/anthropic ",
      apiKey = " test-key ",
      primaryModel = "qwen3.6-plus",
      modelIds = listOf("qwen3.6-plus", "custom-model"),
    )

    val settings = prefs.localModelProviderSettings()

    assertEquals("https://example.test/anthropic", settings.baseUrl)
    assertEquals("test-key", settings.apiKey)
    assertEquals("qwen3.6-plus", settings.primaryModel)
    assertEquals(listOf("qwen3.6-plus", "custom-model"), settings.modelIds.take(2))
    assertFalse(plainPrefs.all.values.any { it.toString().contains("test-key") })
  }

  @Test
  fun localModelProviderConfig_allowsDlaProviderWithProductIntroTokenLimit() {
    val existing =
      Json
        .parseToJsonElement(
          """
          {
            "models": {
              "mode": "merge",
              "providers": {
                "qwen3-dla": {
                  "baseUrl": "http://127.0.0.1:8081/v1",
                  "api": "openai-completions",
                  "models": [{ "id": "qwen3-1.7b-dla" }]
                },
                "dashscope-coding": {
                  "baseUrl": "https://old.example",
                  "models": [{ "id": "old" }]
                }
              }
            },
            "agents": {
              "defaults": {
                "model": { "primary": "qwen3-dla/qwen3-1.7b-dla" },
                "models": { "qwen3-dla/qwen3-1.7b-dla": {} }
              },
              "list": [
                { "id": "main", "default": true, "model": "qwen3-dla/qwen3-1.7b-dla" }
              ]
            }
          }
          """.trimIndent(),
        ).jsonObject

    val next =
      LocalModelProviderConfig.applyProviderConfig(
        existing = existing,
        settings =
          LocalModelProviderSettings(
            providerId = LocalModelProviderConfig.DLA_PROVIDER_ID,
            baseUrl = LocalModelProviderConfig.DLA_BASE_URL,
            apiKey = LocalModelProviderConfig.DLA_API_KEY,
            primaryModel = LocalModelProviderConfig.DLA_MODEL_ID,
            modelIds = listOf(LocalModelProviderConfig.DLA_MODEL_ID),
          ),
      )

    val providers = next["models"]!!.jsonObject["providers"]!!.jsonObject
    val defaults = next["agents"]!!.jsonObject["defaults"]!!.jsonObject
    val defaultModels = defaults["models"]!!.jsonObject
    val firstAgent = (next["agents"]!!.jsonObject["list"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
    val dlaProvider = providers[LocalModelProviderConfig.DLA_PROVIDER_ID]!!.jsonObject
    val dlaModel = (dlaProvider["models"] as kotlinx.serialization.json.JsonArray)[0].jsonObject

    assertTrue(providers.containsKey(LocalModelProviderConfig.DLA_PROVIDER_ID))
    assertTrue(defaultModels.containsKey("${LocalModelProviderConfig.DLA_PROVIDER_ID}/${LocalModelProviderConfig.DLA_MODEL_ID}"))
    assertEquals(LocalModelProviderConfig.DLA_BASE_URL, dlaProvider["baseUrl"].toString().trim('"'))
    assertEquals(32_768, dlaModel["contextWindow"].toString().toInt())
    assertEquals(384, dlaModel["maxTokens"].toString().toInt())
    assertEquals(
      "${LocalModelProviderConfig.DLA_PROVIDER_ID}/${LocalModelProviderConfig.DLA_MODEL_ID}",
      defaults["model"]!!.jsonObject["primary"].toString().trim('"'),
    )
    assertEquals(
      "${LocalModelProviderConfig.DLA_PROVIDER_ID}/${LocalModelProviderConfig.DLA_MODEL_ID}",
      firstAgent["model"].toString().trim('"'),
    )
  }

  @Test
  fun localModelProviderSync_disablesAutoCompactionForDlaProvider() {
    val home = Files.createTempDirectory("openclaw-dla-home").toFile()

    LocalModelProviderConfig.sync(
      openClawHome = home,
      settings = LocalModelProviderConfig.dlaProviderSettings(),
    )

    val settingsFile = home.resolve(".openclaw/settings.json")
    val settings = Json.parseToJsonElement(settingsFile.readText()).jsonObject
    val compaction = settings["compaction"]!!.jsonObject

    assertEquals(false, compaction["enabled"]!!.jsonPrimitive.booleanOrNull)
  }
}

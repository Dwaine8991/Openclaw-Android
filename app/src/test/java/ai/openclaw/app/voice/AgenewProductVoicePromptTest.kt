package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AgenewProductVoicePromptTest {
  @Test
  fun productQuestionIncludesWorkspaceProductCategories() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("公司都有什么产品", docs)

    assertTrue(prompt.contains("MANDATORY_PRODUCT_LINE_OVERVIEW"))
    assertTrue(prompt.contains("工作区产品资料摘要"))
    assertTrue(prompt.contains("4G SoM"))
    assertTrue(prompt.contains("5G SoM"))
    assertTrue(prompt.contains("AI SoM"))
    assertTrue(prompt.contains("WIFI SoM"))
    assertTrue(prompt.contains("M3281V"))
    assertTrue(prompt.contains("M328L"))
    assertTrue(prompt.contains("M328S"))
    assertTrue(prompt.contains("M274F"))
    assertTrue(prompt.contains("M274K"))
    assertTrue(prompt.contains("VOICE_SHORT_REPLY"))
    assertTrue(prompt.contains("Answer in 2 to 4 short spoken sentences"))
    assertTrue(prompt.contains("宇宁科技目前主要有四类产品"))
    assertTrue(prompt.contains("WIFI SoM：不需要蜂窝网络"))
    assertFalse(prompt.contains("48 TOPS"))
    assertTrue(prompt.length < 1_800)
  }

  @Test
  fun broadProductQuestionRequiresAllFourCategoriesBeforeWorkspaceContext() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("还有什么产品", docs)

    val overviewIndex = prompt.indexOf("MANDATORY_PRODUCT_LINE_OVERVIEW")
    val contextIndex = prompt.indexOf("工作区产品资料摘要")
    assertTrue(overviewIndex >= 0)
    assertTrue(contextIndex >= 0)
    assertTrue(overviewIndex > contextIndex)
    assertTrue(prompt.contains("Do not omit WIFI SoM"))
    assertTrue(prompt.length < 1_800)
  }

  @Test
  fun aiProductQuestionOnlyAddsAiReference() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("AI模组有哪些", docs)

    assertTrue(prompt.contains("AI SoM"))
    assertTrue(prompt.contains("M3281V"))
    assertTrue(prompt.contains("48 TOPS"))
    assertFalse(prompt.contains("M274F"))
    assertFalse(prompt.contains("M293GO"))
    assertTrue(prompt.length < 2_200)
  }

  @Test
  fun wifiProductQuestionOnlyAddsWifiReference() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("WIFI产品有哪些", docs)

    assertTrue(prompt.contains("WIFI SoM"))
    assertTrue(prompt.contains("M274F"))
    assertTrue(prompt.contains("M274K"))
    assertFalse(prompt.contains("48 TOPS"))
    assertFalse(prompt.contains("M293GO"))
    assertTrue(prompt.length < 2_000)
  }

  @Test
  fun casualQuestionIsNotExpanded() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("你好", docs)

    assertEquals("你好", prompt)
  }

  @Test
  fun modelNameQuestionIsDetected() {
    assertTrue(AgenewProductVoicePrompt.isProductQuestion("M3281V 有什么特点"))
    assertTrue(AgenewProductVoicePrompt.isProductQuestion("介绍一下 M 三二八 S"))
    assertFalse(AgenewProductVoicePrompt.isProductQuestion("今天天气怎么样"))
  }

  @Test
  fun spokenChineseModelNameRoutesToSpecificModelReference() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("介绍一下 M 三二八 S", docs)

    assertTrue(prompt.contains("Specific model reference for M328S"))
    assertTrue(prompt.contains("M328S: AI smart module with 10 TOPS"))
    assertFalse(prompt.contains("MANDATORY_PRODUCT_LINE_OVERVIEW"))
    assertFalse(prompt.contains("M274F"))
  }

  private fun createDocs(): File {
    val root = createTempDirectory().toFile()
    File(root, "product-categories.md").writeText(
      """
      ## 4G SoM
      H1502BQ, H1503RQ.
      ## 5G SoM
      M293GO, M318GO.
      ## AI SoM
      M3281V, M328L, M328S.
      ## WIFI SoM
      M274F, M274K.
      """.trimIndent(),
    )
    File(root, "product-catalog.md").writeText("M3281V: AI smart module with 48 TOPS.")
    File(root, "product-briefs.md").writeText(
      """
      M3281V: AI smart module with 48 TOPS.
      M328L: AI smart module with 10 TOPS.
      M328S: AI smart module with 10 TOPS.
      M274F: Wi-Fi smart module.
      M274K: Wi-Fi smart module.
      M293GO: 5G smart module.
      """.trimIndent(),
    )
    File(root, "model-aliases.md").writeText("M 328 1V -> M3281V.")
    return root
  }
}

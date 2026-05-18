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

    assertTrue(prompt.contains("CANONICAL_PRODUCT_OVERVIEW_ANSWER"))
    assertTrue(prompt.contains("宇宁科技主要有四类产品"))
    assertTrue(prompt.contains("4G SoM：H1502/H1503/H1504/H1641/M1642 系列"))
    assertTrue(prompt.contains("5G SoM：M293GO、M318GO"))
    assertTrue(prompt.contains("AI SoM：M3281V、M328L、M328S"))
    assertTrue(prompt.contains("WIFI SoM：M274F、M274K"))
    assertTrue(prompt.contains("PRODUCT_KB_SNIPPET type=overview"))
    assertFalse(prompt.contains(AgenewProductFacts.overviewReference()))
    assertTrue(prompt.contains("工作区产品资料摘要:"))
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
    assertTrue(prompt.contains("completeness of required categories/models is more important than sentence count"))
    assertFalse(prompt.contains("H1502BQ, H1502RQ, H1502UQ"))
    assertFalse(prompt.contains("48 TOPS"))
    assertTrue(prompt.length < 1_100)
  }

  @Test
  fun broadProductQuestionRequiresAllFourCategoriesBeforeWorkspaceContext() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("还有什么产品", docs)

    val overviewIndex = prompt.indexOf("PRODUCT_KB_SNIPPET type=overview")
    val contextIndex = prompt.indexOf("工作区产品资料摘要:")
    assertTrue(overviewIndex >= 0)
    assertTrue(contextIndex >= 0)
    assertTrue(overviewIndex > contextIndex)
    assertTrue(prompt.contains("CANONICAL_PRODUCT_OVERVIEW_ANSWER"))
    assertTrue(prompt.contains("do not drop any listed category or model"))
    assertFalse(prompt.contains("H1502BQ, H1502RQ, H1502UQ"))
    assertTrue(prompt.length < 1_100)
  }

  @Test
  fun aiProductQuestionOnlyAddsAiReference() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("AI模组有哪些", docs)

    assertTrue(prompt.contains("PRODUCT_KB_SNIPPET type=category category=AI SoM"))
    assertTrue(prompt.contains("AI SoM"))
    assertTrue(prompt.contains("M3281V"))
    assertTrue(prompt.contains("48 TOPS"))
    assertFalse(prompt.contains("M274F"))
    assertFalse(prompt.contains("M293GO"))
    assertTrue(prompt.length < 1_200)
  }

  @Test
  fun wifiProductQuestionOnlyAddsWifiReference() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("WIFI产品有哪些", docs)

    assertTrue(prompt.contains("PRODUCT_KB_SNIPPET type=category category=WIFI SoM"))
    assertTrue(prompt.contains("WIFI SoM"))
    assertTrue(prompt.contains("M274F"))
    assertTrue(prompt.contains("M274K"))
    assertFalse(prompt.contains("48 TOPS"))
    assertFalse(prompt.contains("M293GO"))
    assertTrue(prompt.length < 1_100)
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

  @Test
  fun exactModelQuestionUsesSingleModelKnowledgeSnippet() {
    val docs = createDocs()

    val prompt = AgenewProductVoicePrompt.build("M318GO 有什么特点", docs)

    assertTrue(prompt.contains("PRODUCT_KB_SNIPPET type=model model=M318GO"))
    assertTrue(prompt.contains("Category: 5G SoM"))
    assertTrue(prompt.contains("MediaTek MT8791"))
    assertFalse(prompt.contains("PRODUCT_KB_SNIPPET type=overview"))
    assertFalse(prompt.contains("M274F"))
    assertTrue(prompt.length < 1_000)
  }

  @Test
  fun factsTableContainsCanonicalCategoriesAndModels() {
    assertEquals(listOf("4G SoM", "5G SoM", "AI SoM", "WIFI SoM"), AgenewProductFacts.categoryNames())
    assertTrue(AgenewProductFacts.modelsForCategory("AI SoM").containsAll(listOf("M3281V", "M328L", "M328S")))
    assertTrue(AgenewProductFacts.modelsForCategory("WIFI SoM").containsAll(listOf("M274F", "M274K")))
    assertTrue(AgenewProductFacts.allModels().contains("H1502BQ"))
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

package ai.openclaw.app.voice

import java.io.File
import java.util.Locale

internal object AgenewProductVoicePrompt {
  fun build(
    userText: String,
    productDocsDir: File,
  ): String {
    val question = userText.trim()
    if (question.isEmpty() || !isProductQuestion(question)) return userText
    val reference = compactReferenceFor(question, productDocsDir)
    if (reference.isBlank()) return userText
    return buildString {
      appendLine("VOICE_SHORT_REPLY. You are the AgenewTech showroom product guide. Use Chinese when the user speaks Chinese.")
      appendLine("Use only the reference facts below. If CANONICAL_PRODUCT_OVERVIEW_ANSWER is present, start from it and do not drop any listed category or model.")
      appendLine("Keep answers concise for speech, but completeness of required categories/models is more important than sentence count.")
      appendLine()
      appendLine("User question:")
      appendLine(question)
      appendLine()
      appendLine("工作区产品资料摘要:")
      append(reference)
    }
  }

  internal fun isProductQuestion(text: String): Boolean {
    val normalized = text.lowercase(Locale.US)
    val productKeywords =
      listOf(
        "公司",
        "产品",
        "产品线",
        "型号",
        "模组",
        "模块",
        "som",
        "wifi",
        "wi-fi",
        "4g",
        "5g",
        "ai",
        "android",
        "介绍",
        "推荐",
        "选型",
        "参数",
        "规格",
        "摄像头",
        "显示",
        "音频",
        "接口",
      )
    if (productKeywords.any { normalized.contains(it.lowercase(Locale.US)) }) return true
    return normalizedModelCode(text) != null
  }

  private fun compactReferenceFor(
    question: String,
    productDocsDir: File,
  ): String {
    val model = normalizedModelCode(question)
    if (model != null) return selectedModelReference(model, productDocsDir)
    val lower = question.lowercase(Locale.US)
    return when {
      lower.contains("wifi") || lower.contains("wi-fi") || question.contains("无线") -> AgenewProductFacts.categoryKnowledgeSnippet("WIFI SoM")
      lower.contains("ai") || question.contains("算力") || question.contains("智能计算") -> AgenewProductFacts.categoryKnowledgeSnippet("AI SoM")
      lower.contains("5g") -> AgenewProductFacts.categoryKnowledgeSnippet("5G SoM")
      lower.contains("4g") || question.contains("蜂窝") || question.contains("联网") -> AgenewProductFacts.categoryKnowledgeSnippet("4G SoM")
      isBroadProductOverviewQuestion(question) -> AgenewProductFacts.overviewKnowledgeSnippet()
      else -> AgenewProductFacts.overviewKnowledgeSnippet()
    }
  }

  private fun selectedModelReference(
    model: String,
    productDocsDir: File,
  ): String {
    val brief = modelBriefFromDocs(model, productDocsDir)
    if (brief.isNotBlank()) return brief
    return AgenewProductFacts.modelKnowledgeSnippet(model)
  }

  private fun modelBriefFromDocs(
    model: String,
    productDocsDir: File,
  ): String {
    val files = listOf("product-briefs.md", "product-catalog.md", "model-aliases.md")
    val lines =
      files.flatMap { name ->
        val file = File(productDocsDir, name)
        runCatching {
          file
            .takeIf { it.exists() && it.length() > 0L }
            ?.readLines()
            .orEmpty()
        }.getOrDefault(emptyList())
      }
    val selected =
      lines
        .filter { it.contains(model, ignoreCase = true) }
        .take(10)
        .joinToString("\n")
        .trim()
    if (selected.isBlank()) return ""
    return "PRODUCT_KB_SNIPPET type=model model=$model source=workspace\nSpecific model reference for $model:\n$selected"
  }

  private fun isBroadProductOverviewQuestion(text: String): Boolean {
    val normalized = text.lowercase(Locale.US)
    val broadTerms = listOf("有什么产品", "哪些产品", "产品线", "产品分类", "产品类别", "公司产品", "还有什么产品")
    if (broadTerms.any { normalized.contains(it.lowercase(Locale.US)) }) return true
    return normalized.contains("产品") && normalizedModelCode(text) == null
  }

  private val MODEL_CODE_REGEX = Regex("\\b[hm]\\d{3,5}[a-z0-9]*\\b", RegexOption.IGNORE_CASE)
  private val SPOKEN_MODEL_CODE_REGEX = Regex("[HhMm][\\s\\u3000]*([零〇一二两三四五六七八九0-9][\\s\\u3000]*){3,5}[A-Za-z]?", RegexOption.IGNORE_CASE)

  private fun normalizedModelCode(text: String): String? {
    MODEL_CODE_REGEX.find(text)?.let { return it.value.uppercase(Locale.US) }
    val match = SPOKEN_MODEL_CODE_REGEX.find(text)?.value ?: return null
    val normalized =
      buildString {
        match.forEach { char ->
          when (char) {
            '零', '〇', '0' -> append('0')
            '一', '1' -> append('1')
            '二', '两', '2' -> append('2')
            '三', '3' -> append('3')
            '四', '4' -> append('4')
            '五', '5' -> append('5')
            '六', '6' -> append('6')
            '七', '7' -> append('7')
            '八', '8' -> append('8')
            '九', '9' -> append('9')
            else -> if (char.isLetter()) append(char.uppercaseChar())
          }
        }
      }
    return normalized.takeIf { MODEL_CODE_REGEX.matches(it) || it in AgenewProductFacts.allModels() }
  }
}

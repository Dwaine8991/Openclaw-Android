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
      appendLine("VOICE_SHORT_REPLY. You are the AgenewTech showroom product guide. Answer in 2 to 4 short spoken sentences; use Chinese when the user speaks Chinese.")
      appendLine("Use only the reference facts below. Keep required categories/models, but skip filler and do not invent missing specs.")
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
      lower.contains("wifi") || lower.contains("wi-fi") || question.contains("无线") -> WIFI_REFERENCE
      lower.contains("ai") || question.contains("算力") || question.contains("智能计算") -> AI_REFERENCE
      lower.contains("5g") -> FIVE_G_REFERENCE
      lower.contains("4g") || question.contains("蜂窝") || question.contains("联网") -> FOUR_G_REFERENCE
      isBroadProductOverviewQuestion(question) -> COMPACT_OVERVIEW_REFERENCE
      else -> COMPACT_OVERVIEW_REFERENCE
    }
  }

  private fun selectedModelReference(
    model: String,
    productDocsDir: File,
  ): String {
    val brief = modelBriefFromDocs(model, productDocsDir)
    if (brief.isNotBlank()) return brief
    return when {
      model in AI_MODELS -> AI_REFERENCE
      model in WIFI_MODELS -> WIFI_REFERENCE
      model in FIVE_G_MODELS -> FIVE_G_REFERENCE
      model in FOUR_G_MODELS -> FOUR_G_REFERENCE
      else -> COMPACT_OVERVIEW_REFERENCE
    }
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
    return "Specific model reference for $model:\n$selected"
  }

  private fun isBroadProductOverviewQuestion(text: String): Boolean {
    val normalized = text.lowercase(Locale.US)
    val broadTerms = listOf("有什么产品", "哪些产品", "产品线", "产品分类", "产品类别", "公司产品", "还有什么产品")
    if (broadTerms.any { normalized.contains(it.lowercase(Locale.US)) }) return true
    return normalized.contains("产品") && normalizedModelCode(text) == null
  }

  private val MODEL_CODE_REGEX = Regex("\\b[hm]\\d{3,5}[a-z0-9]*\\b", RegexOption.IGNORE_CASE)
  private val SPOKEN_MODEL_CODE_REGEX = Regex("[HhMm][\\s\\u3000]*([零〇一二三四五六七八九0-9][\\s\\u3000]*){3,5}[A-Za-z]?", RegexOption.IGNORE_CASE)

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
    return normalized.takeIf { MODEL_CODE_REGEX.matches(it) || it in ALL_MODELS }
  }

  private val FOUR_G_MODELS =
    setOf("H1502BQ", "H1502RQ", "H1502UQ", "H1503BQ", "H1503RQ", "H1503UQ", "H1504TQ", "H1641BP", "H1641UP", "H1641RP", "H164YP", "M1642ZP")
  private val FIVE_G_MODELS = setOf("M293GO", "M318GO")
  private val AI_MODELS = setOf("M3281V", "M328L", "M328S")
  private val WIFI_MODELS = setOf("M274F", "M274K")
  private val ALL_MODELS = FOUR_G_MODELS + FIVE_G_MODELS + AI_MODELS + WIFI_MODELS

  private val COMPACT_OVERVIEW_REFERENCE =
    """
    MANDATORY_PRODUCT_LINE_OVERVIEW
    Do not omit WIFI SoM. Do not say AI SoM has no representative models.
    宇宁科技目前主要有四类产品：
    1. 4G SoM: LTE Android smart modules for cellular + Wi-Fi/Bluetooth, GNSS, multimedia and peripherals. Models: H1502BQ, H1502RQ, H1502UQ, H1503BQ, H1503RQ, H1503UQ, H1504TQ, H1641BP, H1641UP, H1641RP, H164YP, M1642ZP.
    2. 5G SoM: 5G NR Android smart modules for higher bandwidth, 5G, Wi-Fi 6 and edge applications. Models: M293GO, M318GO.
    3. AI SoM: Android AI/smart-computing modules for edge AI, meetings, robots, AR/VR, live streaming and high-compute terminals. Models: M3281V, M328L, M328S.
    4. WIFI SoM：不需要蜂窝网络、以 Wi-Fi 连接为主的 Android 智能模组. Models: M274F, M274K.
    End by asking whether the user cares most about connectivity, AI performance, display/camera/audio I/O, region, size or power.
    """.trimIndent()

  private val OVERVIEW_REFERENCE =
    """
    MANDATORY_PRODUCT_LINE_OVERVIEW
    For this broad product-line question, the answer MUST include all four categories below, in this order.
    Do not omit WIFI SoM. Do not say AI SoM has no representative models.
    Canonical Chinese answer to follow closely:
    宇宁科技目前主要有四类产品：
    1. 4G SoM：LTE Android 智能模组，面向需要蜂窝网络、Wi-Fi/蓝牙、GNSS、多媒体和外设接口的终端。代表型号有 H1502BQ、H1502RQ、H1502UQ、H1503BQ、H1503RQ、H1503UQ、H1504TQ、H1641BP、H1641UP、H1641RP、H164YP、M1642ZP。
    2. 5G SoM：5G NR Android 智能模组，面向需要更高带宽、5G 连接、Wi-Fi 6 和边缘应用的终端。代表型号有 M293GO、M318GO。
    3. AI SoM：高性能 Android AI/智能计算模组，面向边缘 AI、会议、机器人、AR/VR、直播和高算力终端。代表型号有 M3281V、M328L、M328S。
    4. WIFI SoM：不需要蜂窝网络、以 Wi-Fi 连接为主的 Android 智能模组，面向显示、摄像头、音频和外设丰富的设备。代表型号有 M274F、M274K。
    最后询问客户更关注联网方式、AI 算力、显示/摄像头/音频接口、目标区域还是尺寸功耗，以便继续选型。
    """.trimIndent()

  private val AI_REFERENCE =
    """
    AI SoM reference:
    - M3281V: high-performance smart-computing module, Android 15, MT8893 platform, 48 TOPS AI performance, 16GB LPDDR5X + 128GB UFS.
    - M328L: Android 15 AI/smart-computing module, MT8371 platform, 10 TOPS AI performance, Wi-Fi 6E/蓝牙 5.3, 8GB LPDDR5X + 128GB UFS.
    - M328S: Android 15 AI/smart-computing module, MT8391 platform, 10 TOPS AI performance, 8GB LPDDR5X + 128GB UFS.
    Answer with these representative models. Do not say AI SoM has no model.
    """.trimIndent()

  private val WIFI_REFERENCE =
    """
    WIFI SoM reference:
    - M274F: Android 13 Wi-Fi smart module, Wi-Fi 6/蓝牙 5.2, 8GB LPDDR4X + 64GB eMMC.
    - M274K: Android 13 Wi-Fi smart module, Wi-Fi 6/蓝牙 5.2, 64GB eMMC, LPDDR4X or DDR4 memory options.
    Positioning: for Android devices that do not need cellular networking but need Wi-Fi/蓝牙, display, camera, audio, and rich peripheral interfaces.
    """.trimIndent()

  private val FIVE_G_REFERENCE =
    """
    5G SoM reference:
    - M293GO: Android 13 smart 5G module, MediaTek MT8791T platform, 5G NR, Wi-Fi 6/蓝牙 5.2, 64GB UFS + 4GB LPDDR4X.
    - M318GO: Android 13 smart 5G module, MediaTek MT8791 platform, 5G NR, Wi-Fi 6/蓝牙 5.2, 64GB UFS + 4GB LPDDR4X.
    Positioning: for terminals needing NSA/SA 5G, higher bandwidth, GNSS, Wi-Fi 6, and Android edge applications.
    """.trimIndent()

  private val FOUR_G_REFERENCE =
    """
    4G SoM reference:
    - LTE Android smart modules for products needing cellular connectivity, Wi-Fi/蓝牙, GNSS, multimedia, and peripheral interfaces.
    - Representative models: H1502BQ, H1502RQ, H1502UQ, H1503BQ, H1503RQ, H1503UQ, H1504TQ, H1641BP, H1641UP, H1641RP, H164YP, M1642ZP.
    - Typical use: connected Android terminals, handheld devices, POS-like devices, industrial/consumer smart products, and regional cellular deployments.
    """.trimIndent()
}

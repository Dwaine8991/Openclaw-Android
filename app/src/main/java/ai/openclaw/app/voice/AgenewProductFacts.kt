package ai.openclaw.app.voice

import java.util.Locale

internal object AgenewProductFacts {
  private data class Category(
    val name: String,
    val positioning: String,
    val models: List<String>,
    val modelFacts: Map<String, String> = emptyMap(),
  )

  private val categories =
    listOf(
      Category(
        name = "4G SoM",
        positioning = "LTE Android smart modules for cellular networking, Wi-Fi/Bluetooth, GNSS, multimedia, and peripheral interfaces.",
        models =
          listOf(
            "H1502BQ",
            "H1502RQ",
            "H1502UQ",
            "H1503BQ",
            "H1503RQ",
            "H1503UQ",
            "H1504TQ",
            "H1641BP",
            "H1641UP",
            "H1641RP",
            "H164YP",
            "M1642ZP",
          ),
      ),
      Category(
        name = "5G SoM",
        positioning = "5G NR Android smart modules for higher bandwidth, 5G connectivity, Wi-Fi 6, GNSS, and edge applications.",
        models = listOf("M293GO", "M318GO"),
        modelFacts =
          mapOf(
            "M293GO" to "Android 13, MediaTek MT8791T, 5G NR, Wi-Fi 6/Bluetooth 5.2, 64GB UFS + 4GB LPDDR4X.",
            "M318GO" to "Android 13, MediaTek MT8791, 5G NR, Wi-Fi 6/Bluetooth 5.2, 64GB UFS + 4GB LPDDR4X.",
          ),
      ),
      Category(
        name = "AI SoM",
        positioning = "Android AI/smart-computing modules for edge AI, meetings, robots, AR/VR, live streaming, and high-compute terminals.",
        models = listOf("M3281V", "M328L", "M328S"),
        modelFacts =
          mapOf(
            "M3281V" to "Android 15, MT8893, 48 TOPS AI performance, 16GB LPDDR5X + 128GB UFS.",
            "M328L" to "Android 15, MT8371, 10 TOPS AI performance, Wi-Fi 6E/Bluetooth 5.3, 8GB LPDDR5X + 128GB UFS.",
            "M328S" to "Android 15, MT8391, 10 TOPS AI performance, 8GB LPDDR5X + 128GB UFS.",
          ),
      ),
      Category(
        name = "WIFI SoM",
        positioning = "不需要蜂窝网络、以 Wi-Fi 连接为主的 Android 智能模组，适合显示、摄像头、音频和外设丰富的设备。",
        models = listOf("M274F", "M274K"),
        modelFacts =
          mapOf(
            "M274F" to "Android 13 Wi-Fi smart module, Wi-Fi 6/Bluetooth 5.2, 8GB LPDDR4X + 64GB eMMC.",
            "M274K" to "Android 13 Wi-Fi smart module, Wi-Fi 6/Bluetooth 5.2, 64GB eMMC, LPDDR4X or DDR4 memory options.",
          ),
      ),
    )

  fun categoryNames(): List<String> = categories.map { it.name }

  fun modelsForCategory(name: String): List<String> =
    categories.firstOrNull { it.name.equals(name, ignoreCase = true) }?.models.orEmpty()

  fun allModels(): Set<String> = categories.flatMap { it.models }.toSet()

  fun categoryNameForModel(model: String): String? {
    val normalized = model.uppercase(Locale.US)
    return categories.firstOrNull { normalized in it.models }?.name
  }

  fun canonicalOverviewAnswer(): String =
    """
    CANONICAL_PRODUCT_OVERVIEW_ANSWER
    宇宁科技主要有四类产品：
    4G SoM：H1502/H1503/H1504/H1641/M1642 系列；
    5G SoM：M293GO、M318GO；
    AI SoM：M3281V、M328L、M328S；
    WIFI SoM：M274F、M274K。
    如果您关注联网、AI算力、显示摄像头、音频接口、尺寸或功耗，我可以继续帮您选型。
    """.trimIndent()

  fun overviewKnowledgeSnippet(): String =
    buildString {
      appendLine("PRODUCT_KB_SNIPPET type=overview")
      appendLine(canonicalOverviewAnswer())
      append("Instruction: answer from this exact overview; do not drop any listed category or model.")
    }.trim()

  fun categoryKnowledgeSnippet(name: String): String {
    val category = categories.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return overviewKnowledgeSnippet()
    return buildString {
      appendLine("PRODUCT_KB_SNIPPET type=category category=${category.name}")
      appendLine("Category: ${category.name}")
      appendLine("Positioning: ${category.positioning}")
      appendLine("Representative models: ${category.models.joinToString(", ")}.")
      appendLine("Required answer: mention these representative models: ${category.models.joinToString(", ")}.")
      category.modelFacts.forEach { (model, fact) -> appendLine("$model: $fact") }
      append("Instruction: answer only this category unless the user asks for a comparison.")
    }.trim()
  }

  fun modelKnowledgeSnippet(model: String): String {
    val normalized = model.uppercase(Locale.US)
    val category = categories.firstOrNull { normalized in it.models } ?: return overviewKnowledgeSnippet()
    val fact = category.modelFacts[normalized]
    return buildString {
      appendLine("PRODUCT_KB_SNIPPET type=model model=$normalized")
      appendLine("Specific model reference for $normalized:")
      if (fact != null) appendLine("$normalized: $fact")
      appendLine("Category: ${category.name}.")
      appendLine("Positioning: ${category.positioning}")
      append("Instruction: answer this model first; if a spec is absent, say the current materials do not provide it.")
    }.trim()
  }

  fun overviewReference(): String =
    buildString {
      appendLine("MANDATORY_PRODUCT_LINE_OVERVIEW")
      appendLine("For broad product overview questions, answer with CANONICAL_PRODUCT_OVERVIEW_ANSWER first. You may lightly polish wording, but must not remove any category or model.")
      appendLine(canonicalOverviewAnswer())
      appendLine("宇宁科技目前主要有四类产品：")
      categories.forEachIndexed { index, category ->
        append(index + 1)
        append(". ")
        append(category.name)
        append(": ")
        append(category.positioning)
        append(" Models: ")
        append(category.models.joinToString(", "))
        appendLine(".")
      }
      append("End by asking whether the user cares most about connectivity, AI performance, display/camera/audio I/O, region, size or power.")
    }.trim()

  fun referenceForCategory(name: String): String {
    val category = categories.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return overviewReference()
    return buildString {
      appendLine("${category.name} reference:")
      appendLine("- Positioning: ${category.positioning}")
      appendLine("- Representative models: ${category.models.joinToString(", ")}.")
      category.modelFacts.forEach { (model, fact) -> appendLine("- $model: $fact") }
    }.trim()
  }

  fun referenceForModel(model: String): String {
    val normalized = model.uppercase(Locale.US)
    val category = categories.firstOrNull { normalized in it.models } ?: return overviewReference()
    val fact = category.modelFacts[normalized]
    return buildString {
      appendLine("Specific model reference for $normalized:")
      if (fact != null) {
        appendLine("- $normalized: $fact")
      }
      appendLine("- Category: ${category.name}.")
      append("- Positioning: ${category.positioning}")
    }.trim()
  }
}

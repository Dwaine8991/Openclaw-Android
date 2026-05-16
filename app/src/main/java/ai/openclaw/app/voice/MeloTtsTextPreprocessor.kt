package ai.openclaw.app.voice

internal object MeloTtsTextPreprocessor {
  private const val MIN_CHUNK_CHARS = 16
  private const val MAX_CHUNK_CHARS = 18
  private const val HARD_CHUNK_CHARS = 28

  private val commonEnglishAbbreviations =
    setOf(
      "co",
      "corp",
      "dr",
      "e.g",
      "etc",
      "i.e",
      "inc",
      "ltd",
      "mr",
      "mrs",
      "ms",
      "no",
      "prof",
      "sr",
      "st",
      "vs",
    )

  fun sanitize(text: String): String =
    normalizeStructure(text)
      .filter { char ->
        val type = Character.getType(char)
        type != Character.SURROGATE.toInt() &&
          type != Character.OTHER_SYMBOL.toInt() &&
          type != Character.NON_SPACING_MARK.toInt()
      }.replace(Regex("\\s+"), " ")
      .replace(Regex("\\s*([\\u3001\\u3002\\uff0c\\uff1b\\uff1a\\uff01\\uff1f])\\s*"), "$1")
      .replace(Regex("([\\u3002\\uff01\\uff1f!?]){2,}"), "$1")
      .trim()
      .trimStart(',', '.', ';', ':', '\u3001', '\u3002', '\uff0c', '\uff1b', '\uff1a')

  fun split(text: String): List<String> {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
      val chunk = current.toString().trim()
      if (chunk.isNotEmpty()) chunks.add(chunk)
      current.clear()
    }

    fun splitAt(indexInclusive: Int) {
      val head = current.substring(0, indexInclusive + 1).trim()
      val tail = current.substring(indexInclusive + 1).trimStart()
      if (head.isNotEmpty()) chunks.add(head)
      current.clear()
      current.append(tail)
    }

    normalized.forEachIndexed { index, char ->
      current.append(char)
      val currentText = current.toString()
      when {
        isStrongBreak(normalized, index) -> flush()
        current.length >= MIN_CHUNK_CHARS && isSoftBreak(char) -> flush()
        current.length >= MAX_CHUNK_CHARS && lastNaturalBoundary(currentText) >= MIN_CHUNK_CHARS -> {
          val boundary = lastNaturalBoundary(currentText)
          splitAt(boundary)
        }
        current.length >= HARD_CHUNK_CHARS -> flush()
      }
    }
    flush()
    return chunks
  }

  fun nextStreamingBoundary(
    text: String,
    start: Int,
    force: Boolean,
  ): Int? {
    if (start >= text.length) return null
    for (index in start until text.length) {
      val chars = index + 1 - start
      val char = text[index]
      if (isStrongBreak(text, index)) return index + 1
      if (chars >= MIN_CHUNK_CHARS && isSoftBreak(char)) return index + 1
      if (!force && chars >= MAX_CHUNK_CHARS) {
        val futureBoundary = naturalBoundaryInWindow(text, start, minOf(text.lastIndex, index + (HARD_CHUNK_CHARS - MAX_CHUNK_CHARS)))
        if (futureBoundary != null) return futureBoundary
        if (chars >= HARD_CHUNK_CHARS) return index + 1
      }
    }
    return if (force && text.length > start) text.length else null
  }

  private fun normalizeStructure(text: String): String =
    text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace(Regex("\\n\\s*[-*+]\\s*"), "\u3002")
      .replace(Regex("(?m)^\\s*[-*+]\\s*"), "")
      .replace(Regex("\\n+"), "\u3002")
      .replace(Regex("[*_`#>\\[\\]{}()]+"), " ")
      .replace(Regex("[\\u2013\\u2014]+"), "\uff0c")

  private fun lastNaturalBoundary(text: String): Int {
    for (index in text.lastIndex downTo 0) {
      if (isStrongBreak(text, index) || isSoftBreak(text[index]) || text[index].isWhitespace()) return index
    }
    return -1
  }

  private fun naturalBoundaryInWindow(
    text: String,
    start: Int,
    endInclusive: Int,
  ): Int? {
    for (index in endInclusive downTo start + MIN_CHUNK_CHARS) {
      if (isStrongBreak(text, index) || isSoftBreak(text[index]) || text[index].isWhitespace()) return index + 1
    }
    return null
  }

  private fun isStrongBreak(
    text: String,
    index: Int,
  ): Boolean {
    val char = text[index]
    return char == '\u3002' ||
      char == '\uff01' ||
      char == '\uff1f' ||
      char == '!' ||
      char == '?' ||
      char == '\n' ||
      (char == '.' && isSentencePeriod(text, index))
  }

  private fun isSentencePeriod(
    text: String,
    index: Int,
  ): Boolean {
    val previous = text.getOrNull(index - 1)
    val next = text.getOrNull(index + 1)
    if (previous?.isDigit() == true && next?.isDigit() == true) return false
    if (previous?.isLetterOrDigit() == true && next?.isLetterOrDigit() == true) return false
    val word = previousWordBeforePeriod(text, index).lowercase()
    if (word in commonEnglishAbbreviations) return false
    return true
  }

  private fun previousWordBeforePeriod(
    text: String,
    periodIndex: Int,
  ): String {
    var start = periodIndex - 1
    while (start >= 0 && (text[start].isLetter() || text[start] == '.')) {
      start -= 1
    }
    return text.substring(start + 1, periodIndex)
  }

  private fun isSoftBreak(char: Char): Boolean =
    char == '\uff1b' ||
      char == ';' ||
      char == '\uff0c' ||
      char == ',' ||
      char == '\u3001' ||
      char == '\uff1a' ||
      char == ':'
}

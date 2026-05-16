package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeloTtsTextPreprocessorTest {
  @Test
  fun splitKeepsDecimalAndProductSentenceTogether() {
    val text = "The Q5+ supports 3.5mm audio and Wi-Fi 6."
    val chunks = MeloTtsTextPreprocessor.split(text)

    assertEquals(text, chunks.joinToString(" "))
    assertTrue(chunks.none { it.endsWith("3.") || it.startsWith("-") })
  }

  @Test
  fun streamingBoundaryUsesEarlyCommaForResponsiveFirstAudio() {
    val text = "It supports Wi-Fi, Android cameras and displays."

    val boundary = MeloTtsTextPreprocessor.nextStreamingBoundary(text, start = 0, force = false)

    assertEquals("It supports Wi-Fi,", text.substring(0, requireNotNull(boundary)))
  }

  @Test
  fun splitKeepsCommonEnglishAbbreviationInsideChunk() {
    val chunks = MeloTtsTextPreprocessor.split("AgenewTech Inc. provides WiFi SoM modules for Android devices.")

    assertTrue(chunks.first().contains("AgenewTech Inc."))
  }

  @Test
  fun splitHardCapsShortSentenceWithoutPunctuationForLowerFirstLatency() {
    val chunks = MeloTtsTextPreprocessor.split("AgenewTech provides smart WiFi SoM products")

    assertTrue(chunks.size > 1)
    assertTrue(chunks.first().length <= 28)
  }
}

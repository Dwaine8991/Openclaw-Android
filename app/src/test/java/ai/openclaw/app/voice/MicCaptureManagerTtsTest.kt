package ai.openclaw.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MicCaptureManagerTtsTest {
  @Test
  fun chatDeltaStreamsFirstChunkAndFinalSpeaksOnlyRemainingText() =
    runTest {
      val spoken = mutableListOf<String>()
      val manager =
        MicCaptureManager(
          context = RuntimeEnvironment.getApplication(),
          scope = this,
          sendToGateway = { _, _ -> "run-tts" },
          speakAssistantReply = { text -> spoken.add(text) },
          isAssistantTtsReady = { true },
        )
      manager.setPrivateField("pendingRunId", "run-tts")

      manager.handleGatewayEvent(
        "chat",
        chatPayload(
          runId = "run-tts",
          state = "delta",
          text = "This is the first streaming sentence.",
        ),
      )
      advanceUntilIdle()

      manager.handleGatewayEvent(
        "chat",
        chatPayload(
          runId = "run-tts",
          state = "final",
          text = "This is the first streaming sentence. This is the final remaining sentence.",
        ),
      )
      advanceUntilIdle()

      assertEquals(
        listOf(
          "This is the first streaming sentence.",
          "This is the final remaining sentence.",
        ),
        spoken,
      )
    }

  @Test
  fun finalStreamingTtsBatchesQueuedRemainingChunksForTalkModePipeline() =
    runTest {
      val spoken = mutableListOf<String>()
      val manager =
        MicCaptureManager(
          context = RuntimeEnvironment.getApplication(),
          scope = this,
          sendToGateway = { _, _ -> "run-batch" },
          speakAssistantReply = { text -> spoken.add(text) },
          isAssistantTtsReady = { true },
        )
      manager.setPrivateField("pendingRunId", "run-batch")

      manager.handleGatewayEvent(
        "chat",
        chatPayload(
          runId = "run-batch",
          state = "final",
          text = "Second sentence. Third sentence.",
        ),
      )
      advanceUntilIdle()

      assertEquals(listOf("Second sentence. Third sentence."), spoken)
    }

  @Test
  fun abortedChatEventKeepsGeneratedAssistantTextVisible() =
    runTest {
      val manager =
        MicCaptureManager(
          context = RuntimeEnvironment.getApplication(),
          scope = this,
          sendToGateway = { _, _ -> "run-abort" },
          isAssistantTtsReady = { false },
        )
      manager.setPrivateField("pendingRunId", "run-abort")

      manager.handleGatewayEvent(
        "chat",
        chatPayload(
          runId = "run-abort",
          state = "delta",
          text = "Partial product answer that is already visible.",
        ),
      )
      manager.handleGatewayEvent(
        "chat",
        chatPayload(
          runId = "run-abort",
          state = "aborted",
          text = "",
        ),
      )
      advanceUntilIdle()

      val assistant = manager.conversation.value.single { it.role == VoiceConversationRole.Assistant }
      assertEquals("Partial product answer that is already visible.", assistant.text)
      assertFalse(assistant.isStreaming)
    }

  private fun chatPayload(
    runId: String,
    state: String,
    text: String,
  ): String =
    """
    {
      "runId": "$runId",
      "state": "$state",
      "message": {
        "role": "assistant",
        "content": [
          {
            "type": "text",
            "text": "$text"
          }
        ]
      }
    }
    """.trimIndent()

  private fun Any.setPrivateField(
    name: String,
    value: Any?,
  ) {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
  }
}

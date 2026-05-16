package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

enum class VoiceConversationRole {
  User,
  Assistant,
}

data class VoiceConversationEntry(
  val id: String,
  val role: VoiceConversationRole,
  val text: String,
  val isStreaming: Boolean = false,
)

class MicCaptureManager(
  private val context: Context,
  private val scope: CoroutineScope,
  /**
   * Send [message] to the gateway and return the run ID.
   * [onRunIdKnown] is called with the idempotency key *before* the network
   * round-trip so [pendingRunId] is set before any chat events can arrive.
   */
  private val sendToGateway: suspend (message: String, onRunIdKnown: (String) -> Unit) -> String?,
  private val fetchAssistantReplyForMessage: suspend (String) -> String? = { null },
  private val abortPendingReply: suspend (String) -> Unit = {},
  private val speakAssistantReply: suspend (String) -> Unit = {},
  private val stopAssistantSpeech: () -> Unit = {},
  private val isAssistantTtsReady: () -> Boolean = { true },
  private val isAssistantSpeaking: () -> Boolean = { false },
  private val localAsrEngine: LocalMnnAsrEngine? = null,
  private val inputManager: VoiceAudioInputManager? = null,
  private val onMnnAvailabilityChanged: (Boolean) -> Unit = {},
) {
  companion object {
    private const val tag = "MicCapture"
    private const val speechMinSessionMs = 30_000L
    private const val speechCompleteSilenceMs = 1_500L
    private const val speechPossibleSilenceMs = 900L
    private const val transcriptIdleFlushMs = 1_600L
    private const val maxConversationEntries = 40
    private const val pendingRunTimeoutMs = 900_000L
    private const val sendTurnTimeoutMs = 35_000L
    private const val assistantReplyPlaybackTimeoutMs = 180_000L
    private const val mouthCloseSilenceMs = 800L
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val json = Json { ignoreUnknownKeys = true }

  private val _micEnabled = MutableStateFlow(false)
  val micEnabled: StateFlow<Boolean> = _micEnabled

  private val _micCooldown = MutableStateFlow(false)
  val micCooldown: StateFlow<Boolean> = _micCooldown

  private val _isListening = MutableStateFlow(false)
  val isListening: StateFlow<Boolean> = _isListening

  private val _statusText = MutableStateFlow("Mic off")
  val statusText: StateFlow<String> = _statusText

  private val _liveTranscript = MutableStateFlow<String?>(null)
  val liveTranscript: StateFlow<String?> = _liveTranscript

  private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
  val queuedMessages: StateFlow<List<String>> = _queuedMessages

  private val _conversation = MutableStateFlow<List<VoiceConversationEntry>>(emptyList())
  val conversation: StateFlow<List<VoiceConversationEntry>> = _conversation

  private val _inputLevel = MutableStateFlow(0f)
  val inputLevel: StateFlow<Float> = _inputLevel

  private val _isSending = MutableStateFlow(false)
  val isSending: StateFlow<Boolean> = _isSending

  private val messageQueue = ArrayDeque<String>()
  private val messageQueueLock = Any()
  private var flushedPartialTranscript: String? = null
  private var pendingRunId: String? = null
  private var pendingAssistantEntryId: String? = null
  private var emptyFinalLookupRunId: String? = null
  private var gatewayConnected = false
  private var lastLoggedMouthGateOpen: Boolean? = null

  private var recognizer: SpeechRecognizer? = null
  private var usingLocalCapture = false
  private var mouthAsrActive = false
  private var restartJob: Job? = null
  private var drainJob: Job? = null
  private var transcriptFlushJob: Job? = null
  private var pendingRunTimeoutJob: Job? = null
  private var abortingRunId: String? = null
  private var stopRequested = false
  private val ttsPauseLock = Any()
  private var ttsPauseDepth = 0
  private var resumeMicAfterTts = false
  private val replyPauseLock = Any()
  private var replyPauseActive = false
  private var resumeMicAfterReply = false
  private val streamingTtsLock = Any()
  private val streamingTtsQueue = ArrayDeque<String>()
  private var streamingTtsWorker: Job? = null
  private var streamingTtsConsumedChars = 0
  private var streamingTtsLatestText = ""
  private var streamingTtsGeneration = 0L
  private val localCapture: LocalVoiceCapture? by lazy {
    val asr = localAsrEngine ?: return@lazy null
    val inputs = inputManager ?: return@lazy null
    LocalVoiceCapture(
      context = context,
      scope = scope,
      inputManager = inputs,
      asrEngine = asr,
      onStatus = { status -> _statusText.value = status },
      onListening = { listening -> _isListening.value = listening },
      onInputLevel = { level -> _inputLevel.value = level },
      onSpeechStart = {
        if (mouthAsrActive) {
          interruptAssistantSpeechIfNeeded("mouth-asr-start")
          abortPendingReplyForUserSpeech("mouth-asr-start")
          _liveTranscript.value = "Listening..."
          _statusText.value = "Listening"
        } else {
          interruptAssistantSpeechIfNeeded("speech-start")
          abortPendingReplyForUserSpeech("speech-start")
        }
      },
      onPartial = { partial ->
        val trimmed = partial.trim()
        if (trimmed.isNotEmpty()) {
          if (!mouthAsrActive) {
            interruptAssistantSpeechIfNeeded("partial")
            abortPendingReplyForUserSpeech("partial")
          }
          _liveTranscript.value = trimmed
        }
      },
      onFinal = { finalText ->
        val trimmed = finalText.trim()
        if (trimmed.isNotEmpty()) {
          if (!mouthAsrActive) {
            interruptAssistantSpeechIfNeeded("final")
            abortPendingReplyForUserSpeech("final")
          }
          queueRecognizedMessage(trimmed)
          sendQueuedIfIdle()
        }
      },
      onUnavailable = { reason ->
        if (stopRequested || !_micEnabled.value) return@LocalVoiceCapture
        if (mouthAsrActive) {
          onMnnAvailabilityChanged(false)
          usingLocalCapture = false
          _isListening.value = false
          _inputLevel.value = 0f
          _statusText.value = "$reason; mouth ASR requires MNN"
          return@LocalVoiceCapture
        }
        onMnnAvailabilityChanged(false)
        usingLocalCapture = false
        _isListening.value = false
        _inputLevel.value = 0f
        _statusText.value = "$reason; local ASR unavailable"
        _micEnabled.value = false
      },
    )
  }

  private fun enqueueMessage(message: String) {
    synchronized(messageQueueLock) {
      messageQueue.addLast(message)
    }
  }

  private fun snapshotMessageQueue(): List<String> =
    synchronized(messageQueueLock) {
      messageQueue.toList()
    }

  private fun hasQueuedMessages(): Boolean =
    synchronized(messageQueueLock) {
      messageQueue.isNotEmpty()
    }

  private fun firstQueuedMessage(): String? =
    synchronized(messageQueueLock) {
      messageQueue.firstOrNull()
    }

  private fun removeFirstQueuedMessage(): String? =
    synchronized(messageQueueLock) {
      if (messageQueue.isEmpty()) null else messageQueue.removeFirst()
    }

  private fun queuedMessageCount(): Int =
    synchronized(messageQueueLock) {
      messageQueue.size
    }

  fun setMicEnabled(enabled: Boolean) {
    mouthAsrActive = false
    localCapture?.setExternalSpeechGate(false)
    localCapture?.setExternalSpeechGateMode(false)
    if (_micEnabled.value == enabled) return
    _micEnabled.value = enabled
    if (enabled) {
      val pausedForTts =
        synchronized(ttsPauseLock) {
          if (ttsPauseDepth > 0) {
            resumeMicAfterTts = true
            true
          } else {
            false
          }
        }
      if (pausedForTts) {
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        return
      }
      val pausedForReply =
        synchronized(replyPauseLock) {
          if (replyPauseActive) {
            resumeMicAfterReply = true
            true
          } else {
            false
          }
        }
      if (pausedForReply) {
        _statusText.value = "Waiting for reply"
        return
      }
      start()
      sendQueuedIfIdle()
    } else {
      synchronized(replyPauseLock) {
        resumeMicAfterReply = false
      }
      // Give the recognizer time to finish processing buffered audio.
      // Cancel any prior drain to prevent duplicate sends on rapid toggle.
      drainJob?.cancel()
      _micCooldown.value = true
      drainJob =
        scope.launch {
          delay(2000L)
          val wasLocalCapture = usingLocalCapture
          stop()
          // System SpeechRecognizer can lose its final callback on manual stop.
          // The local MNN path must wait for its own full-utterance final ASR
          // instead of sending the partial preview as final text.
          val partial = if (wasLocalCapture) "" else _liveTranscript.value?.trim().orEmpty()
          if (partial.isNotEmpty()) {
            queueRecognizedMessage(partial)
          }
          drainJob = null
          _micCooldown.value = false
          sendQueuedIfIdle()
        }
    }
  }

  fun setMouthAsrActive(active: Boolean) {
    if (mouthAsrActive == active && _micEnabled.value == active) {
      if (active) ensureLocalMouthCaptureRunning("mouth-asr-active-repeat")
      return
    }
    mouthAsrActive = active
    localCapture?.setExternalSpeechGate(false)
    localCapture?.setExternalSpeechGateMode(enabled = active, closeSilenceMs = mouthCloseSilenceMs)
    if (active) {
      _micEnabled.value = true
      start()
      sendQueuedIfIdle()
    } else {
      setMicEnabled(false)
    }
  }

  fun setMouthSpeechGate(open: Boolean) {
    if (!mouthAsrActive) return
    val waitingForAssistantReply = pendingRunId != null || isReplyPauseActive() || _isSending.value
    val canInterruptActiveSpeech =
      pendingRunId == null && !isReplyPauseActive() && (hasStreamingAssistantTtsWork() || isAssistantSpeaking())
    if (waitingForAssistantReply && !canInterruptActiveSpeech) {
      localCapture?.setExternalSpeechGate(false)
      if (lastLoggedMouthGateOpen != open) {
        Log.d(tag, "mouth speech gate ignored open=$open while waiting for reply")
        lastLoggedMouthGateOpen = open
      }
      _statusText.value = "Waiting for reply"
      return
    }
    if (open) {
      interruptAssistantSpeechIfNeeded("mouth-gate-open")
      abortPendingReplyForUserSpeech("mouth-gate-open")
    }
    localCapture?.setExternalSpeechGate(open)
    if (open) ensureLocalMouthCaptureRunning("mouth-gate-open")
    if (lastLoggedMouthGateOpen != open) {
      Log.d(tag, "mouth speech gate open=$open")
      lastLoggedMouthGateOpen = open
    }
    if (!_isSending.value && _micEnabled.value) {
      _statusText.value = if (open) "Mouth speaking" else "Mouth armed"
    }
  }

  suspend fun pauseForTts() {
    if (mouthAsrActive) {
      Log.d(tag, "skip mic pause for TTS while mouth ASR is active")
      return
    }
    val shouldPause =
      synchronized(ttsPauseLock) {
        ttsPauseDepth += 1
        if (ttsPauseDepth > 1) return@synchronized false
        resumeMicAfterTts = _micEnabled.value
        val active = resumeMicAfterTts || recognizer != null || usingLocalCapture || _isListening.value
        if (!active) {
          ttsPauseDepth = 0
          resumeMicAfterTts = false
          return@synchronized false
        }
        stopRequested = true
        restartJob?.cancel()
        restartJob = null
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        _isListening.value = false
        _inputLevel.value = 0f
        _liveTranscript.value = null
        _statusText.value = if (_isSending.value) "Speaking · waiting for reply" else "Speaking…"
        true
      }
    if (!shouldPause) return
    localCapture?.stopAndJoin(finalizePending = false)
    usingLocalCapture = false
    withContext(Dispatchers.Main) {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  suspend fun resumeAfterTts() {
    if (mouthAsrActive) return
    val shouldResume =
      synchronized(ttsPauseLock) {
        if (ttsPauseDepth == 0) return@synchronized false
        ttsPauseDepth -= 1
        if (ttsPauseDepth > 0) return@synchronized false
        val resume = resumeMicAfterTts && _micEnabled.value
        resumeMicAfterTts = false
        if (!resume) {
          _statusText.value =
            when {
              _micEnabled.value && _isSending.value -> "Listening · sending queued voice"
              _micEnabled.value -> "Listening"
              _isSending.value -> "Mic off · sending…"
              else -> "Mic off"
            }
        }
        resume
      }
    if (!shouldResume) return
    if (isReplyPauseActive()) {
      markResumeAfterReply()
      return
    }
    stopRequested = false
    start()
    sendQueuedIfIdle()
  }

  private suspend fun pauseForPendingReply() {
    val shouldPause =
      synchronized(replyPauseLock) {
        if (replyPauseActive) return@synchronized false
        replyPauseActive = true
        resumeMicAfterReply = _micEnabled.value
        stopRequested = true
        restartJob?.cancel()
        restartJob = null
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        _isListening.value = false
        _inputLevel.value = 0f
        _liveTranscript.value = null
        _statusText.value = "Waiting for reply"
        true
      }
    if (!shouldPause) return
    Log.d(tag, "pause mic for pending reply")
    localCapture?.stop(finalizePending = false)
    usingLocalCapture = false
    withContext(Dispatchers.Main) {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun resumeAfterPendingReply() {
    val shouldResume =
      synchronized(replyPauseLock) {
        if (!replyPauseActive) return@synchronized false
        replyPauseActive = false
        val resume = resumeMicAfterReply && _micEnabled.value
        resumeMicAfterReply = false
        if (!resume) {
          _statusText.value =
            when {
              _micEnabled.value && _isSending.value -> "Listening - sending queued voice"
              _micEnabled.value -> "Listening"
              _isSending.value -> "Mic off - sending"
              else -> "Mic off"
            }
        }
        resume
      }
    if (!shouldResume) return
    Log.d(tag, "resume mic after pending reply")
    stopRequested = false
    start()
    sendQueuedIfIdle()
  }

  private fun isReplyPauseActive(): Boolean =
    synchronized(replyPauseLock) {
      replyPauseActive
    }

  private fun markResumeAfterReply() {
    synchronized(replyPauseLock) {
      if (replyPauseActive) resumeMicAfterReply = true
    }
  }

  fun onGatewayConnectionChanged(connected: Boolean) {
    gatewayConnected = connected
    if (connected) {
      sendQueuedIfIdle()
      return
    }
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    resetStreamingAssistantTts(cancelActive = true)
    pendingRunId = null
    pendingAssistantEntryId = null
    emptyFinalLookupRunId = null
    abortingRunId = null
    _isSending.value = false
    if (hasQueuedMessages()) {
      _statusText.value = queuedWaitingStatus()
    }
  }

  fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    if (event != "chat") return
    if (payloadJson.isNullOrBlank()) return
    val payload =
      try {
        json.parseToJsonElement(payloadJson).asObjectOrNull()
      } catch (_: Throwable) {
        null
      } ?: return

    val eventRunId = payload["runId"].asStringOrNull() ?: return
    val state = payload["state"].asStringOrNull()
    val runId =
      pendingRunId ?: run {
        Log.d(tag, "no pendingRunId; drop chat event runId=$eventRunId state=$state")
        return
      }
    if (eventRunId != runId) {
      Log.d("MicCapture", "runId mismatch: event=$eventRunId pending=$runId")
      return
    }

    when (state) {
      "delta" -> {
        val deltaText = parseAssistantText(payload)
        if (!deltaText.isNullOrBlank()) {
          val assistantText = deltaText.trim()
          upsertPendingAssistant(text = assistantText, isStreaming = true)
          queueStreamingAssistantTts(text = assistantText, force = false, reason = "delta")
        }
      }
      "final" -> {
        val finalText = parseAssistantText(payload)?.trim().orEmpty()
        if (finalText.isNotEmpty()) {
          upsertPendingAssistant(text = finalText, isStreaming = false)
          finishPendingTurnAfterReply(finalText)
        } else if (pendingAssistantEntryId != null) {
          val existingText = pendingAssistantText()?.trim().orEmpty()
          if (existingText.isNotEmpty()) {
            updateConversationEntry(pendingAssistantEntryId!!, text = existingText, isStreaming = false)
            finishPendingTurnAfterReply(existingText)
          } else {
            Log.d(tag, "empty final for pending assistant; fetching history")
            finishEmptyFinalAfterHistoryLookup("pending-empty")
          }
        } else {
          Log.d(tag, "empty final without assistant text; fetching history")
          finishEmptyFinalAfterHistoryLookup("no-assistant-text")
        }
      }
      "error" -> {
        val errorMessage =
          payload["errorMessage"]
            .asStringOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { "Voice request failed" }
        upsertPendingAssistant(text = errorMessage, isStreaming = false)
        finishPendingTurnAfterReply(null)
      }
      "aborted" -> {
        val existingText = pendingAssistantText()?.trim().orEmpty()
        if (existingText.isNotEmpty() && pendingAssistantEntryId != null) {
          updateConversationEntry(pendingAssistantEntryId!!, text = existingText, isStreaming = false)
          finishPendingTurnAfterReply(existingText)
        } else {
          upsertPendingAssistant(text = "Response aborted", isStreaming = false)
          finishPendingTurnAfterReply(null)
        }
      }
    }
  }

  private fun start() {
    stopRequested = false
    if (!hasMicPermission()) {
      _statusText.value = "Microphone permission required"
      _micEnabled.value = false
      return
    }
    val local = localCapture
    if (local != null) {
      usingLocalCapture = true
      onMnnAvailabilityChanged(true)
      local.setExternalSpeechGateMode(enabled = mouthAsrActive, closeSilenceMs = mouthCloseSilenceMs)
      _statusText.value = if (mouthAsrActive) "Starting mouth ASR" else "Starting MNN voice"
      local.start()
      return
    }
    if (mouthAsrActive) {
      _statusText.value = "Mouth ASR requires local MNN"
      _micEnabled.value = false
      return
    }
    onMnnAvailabilityChanged(false)
    _statusText.value = "Local MNN ASR unavailable"
    _micEnabled.value = false
  }

  private fun ensureLocalMouthCaptureRunning(reason: String) {
    if (!mouthAsrActive || !_micEnabled.value) return
    val local = localCapture ?: return
    if (local.isRunning()) return
    Log.d(tag, "restart local mouth ASR capture reason=$reason")
    usingLocalCapture = true
    onMnnAvailabilityChanged(true)
    local.setExternalSpeechGateMode(enabled = true, closeSilenceMs = mouthCloseSilenceMs)
    _statusText.value = "Starting mouth ASR"
    local.start()
  }

  private fun startSystemRecognizer() {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _statusText.value = "Speech recognizer unavailable"
      _micEnabled.value = false
      return
    }

    mainHandler.post {
      try {
        if (recognizer == null) {
          recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
        }
        startListeningSession()
      } catch (err: Throwable) {
        _statusText.value = "Start failed: ${err.message ?: err::class.simpleName}"
        _micEnabled.value = false
      }
    }
  }

  private fun stop() {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _isListening.value = false
    _statusText.value = if (_isSending.value) "Mic off · sending..." else "Mic off"
    _inputLevel.value = 0f
    localCapture?.setExternalSpeechGate(false)
    localCapture?.stop()
    usingLocalCapture = false
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun startListeningSession() {
    val recognizerInstance = recognizer ?: return
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, speechMinSessionMs)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, speechCompleteSilenceMs)
        putExtra(
          RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
          speechPossibleSilenceMs,
        )
      }
    _statusText.value =
      when {
        _isSending.value -> "Listening · sending queued voice"
        hasQueuedMessages() -> "Listening · ${queuedMessageCount()} queued"
        else -> "Listening"
      }
    _isListening.value = true
    recognizerInstance.startListening(intent)
  }

  private fun scheduleRestart(delayMs: Long = 300L) {
    if (stopRequested) return
    if (!_micEnabled.value) return
    restartJob?.cancel()
    restartJob =
      scope.launch {
        delay(delayMs)
        mainHandler.post {
          if (stopRequested || !_micEnabled.value) return@post
          if (usingLocalCapture) return@post
          try {
            startListeningSession()
          } catch (_: Throwable) {
            // retry through onError
          }
        }
      }
  }

  private fun queueRecognizedMessage(text: String) {
    val message = text.trim()
    _liveTranscript.value = null
    if (message.isEmpty()) return
    appendConversation(
      role = VoiceConversationRole.User,
      text = message,
    )
    enqueueMessage(message)
    publishQueue()
  }

  private fun scheduleTranscriptFlush(expectedText: String) {
    transcriptFlushJob?.cancel()
    transcriptFlushJob =
      scope.launch {
        delay(transcriptIdleFlushMs)
        if (!_micEnabled.value || _isSending.value) return@launch
        val current = _liveTranscript.value?.trim().orEmpty()
        if (current.isEmpty() || current != expectedText) return@launch
        flushedPartialTranscript = current
        queueRecognizedMessage(current)
        sendQueuedIfIdle()
      }
  }

  private fun publishQueue() {
    _queuedMessages.value = snapshotMessageQueue()
  }

  private fun sendQueuedIfIdle() {
    if (_isSending.value) return
    if (!hasQueuedMessages()) {
      if (_micEnabled.value) {
        _statusText.value = "Listening"
      } else {
        _statusText.value = "Mic off"
      }
      return
    }
    if (!gatewayConnected) {
      _statusText.value = queuedWaitingStatus()
      return
    }

    val next = firstQueuedMessage() ?: return
    interruptAssistantSpeechIfNeeded("new-turn")
    resetStreamingAssistantTts(cancelActive = true)
    _isSending.value = true
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    _statusText.value = if (_micEnabled.value) "Listening · sending queued voice" else "Sending queued voice"

    scope.launch {
      try {
        pauseForPendingReply()
        Log.d(tag, "voice turn send start chars=${next.length}")
        val runId =
          withTimeoutOrNull(sendTurnTimeoutMs) {
            sendToGateway(next) { earlyRunId ->
              // Called with the idempotency key before chat.send fires so that
              // pendingRunId is populated before any chat events can arrive.
              pendingRunId = earlyRunId
            }
          }
        // Update to the real runId if the gateway returned a different one.
        if (runId != null && runId != pendingRunId) pendingRunId = runId
        if (runId == null) {
          Log.w(tag, "voice turn send returned null or timed out")
          pendingRunTimeoutJob?.cancel()
          pendingRunTimeoutJob = null
          resetStreamingAssistantTts(cancelActive = true)
          removeFirstQueuedMessage()
          publishQueue()
          _isSending.value = false
          pendingAssistantEntryId = null
          pendingRunId = null
          emptyFinalLookupRunId = null
          abortingRunId = null
          resumeAfterPendingReply()
          sendQueuedIfIdle()
        } else {
          Log.d(tag, "voice turn send ok runId=$runId")
          armPendingRunTimeout(runId)
        }
      } catch (err: Throwable) {
        Log.w(tag, "voice turn send failed: ${err.message ?: err::class.simpleName}")
        pendingRunTimeoutJob?.cancel()
        pendingRunTimeoutJob = null
        resetStreamingAssistantTts(cancelActive = true)
        _isSending.value = false
        pendingRunId = null
        pendingAssistantEntryId = null
        emptyFinalLookupRunId = null
        abortingRunId = null
        resumeAfterPendingReply()
        _statusText.value =
          if (!gatewayConnected) {
            queuedWaitingStatus()
          } else {
            "Send failed: ${err.message ?: err::class.simpleName}"
          }
      }
    }
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob =
      scope.launch {
        delay(pendingRunTimeoutMs)
        if (pendingRunId != runId) return@launch
        removeFirstQueuedMessage()
        publishQueue()
        pendingRunId = null
        pendingAssistantEntryId = null
        emptyFinalLookupRunId = null
        abortingRunId = null
        _isSending.value = false
        resetStreamingAssistantTts(cancelActive = true)
        _statusText.value =
          if (gatewayConnected) {
            "Voice reply timed out"
          } else {
            queuedWaitingStatus()
          }
        resumeAfterPendingReply()
        sendQueuedIfIdle()
      }
  }

  private fun completePendingTurn(sendNext: Boolean = true) {
    pendingRunTimeoutJob?.cancel()
    pendingRunTimeoutJob = null
    if (removeFirstQueuedMessage() != null) {
      publishQueue()
    }
    pendingRunId = null
    pendingAssistantEntryId = null
    emptyFinalLookupRunId = null
    _isSending.value = false
    if (sendNext) sendQueuedIfIdle()
  }

  private fun queuedWaitingStatus(): String = "${queuedMessageCount()} queued · waiting for gateway"

  private fun appendConversation(
    role: VoiceConversationRole,
    text: String,
    isStreaming: Boolean = false,
  ): String {
    val id = UUID.randomUUID().toString()
    _conversation.value =
      (_conversation.value + VoiceConversationEntry(id = id, role = role, text = text, isStreaming = isStreaming))
        .takeLast(maxConversationEntries)
    return id
  }

  private fun updateConversationEntry(
    id: String,
    text: String?,
    isStreaming: Boolean,
  ) {
    val current = _conversation.value
    if (current.isEmpty()) return

    val targetIndex =
      when {
        current[current.lastIndex].id == id -> current.lastIndex
        else -> current.indexOfFirst { it.id == id }
      }
    if (targetIndex < 0) return

    val entry = current[targetIndex]
    val updatedText = text ?: entry.text
    if (updatedText == entry.text && entry.isStreaming == isStreaming) return
    val updated = current.toMutableList()
    updated[targetIndex] = entry.copy(text = updatedText, isStreaming = isStreaming)
    _conversation.value = updated
  }

  private fun upsertPendingAssistant(
    text: String,
    isStreaming: Boolean,
  ) {
    val currentId = pendingAssistantEntryId
    if (currentId == null) {
      pendingAssistantEntryId =
        appendConversation(
          role = VoiceConversationRole.Assistant,
          text = text,
          isStreaming = isStreaming,
        )
      return
    }
    updateConversationEntry(id = currentId, text = text, isStreaming = isStreaming)
  }

  private fun pendingAssistantText(): String? {
    val currentId = pendingAssistantEntryId ?: return null
    return _conversation.value.firstOrNull { it.id == currentId }?.text
  }

  private fun playAssistantReplyAsync(text: String) {
    val spoken = text.trim()
    if (spoken.isEmpty()) return
    scope.launch {
      try {
        speakAssistantReply(spoken)
      } catch (err: Throwable) {
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      }
    }
  }

  private fun interruptAssistantSpeechIfNeeded(reason: String) {
    val hadWork = hasStreamingAssistantTtsWork() || isAssistantSpeaking()
    Log.d(tag, "interrupt assistant speech reason=$reason hadWork=$hadWork")
    resetStreamingAssistantTts(cancelActive = true)
    runCatching { stopAssistantSpeech() }
      .onFailure { err -> Log.w(tag, "stop assistant speech failed: ${err.message ?: err::class.simpleName}") }
  }

  private fun abortPendingReplyForUserSpeech(reason: String) {
    val runId = pendingRunId ?: return
    if (abortingRunId == runId) return
    abortingRunId = runId
    Log.d(tag, "abort pending reply reason=$reason runId=$runId")
    _statusText.value = "Listening - interrupting reply"
    scope.launch {
      try {
        abortPendingReply(runId)
      } catch (err: Throwable) {
        Log.w(tag, "abort pending reply failed: ${err.message ?: err::class.simpleName}")
      } finally {
        if (pendingRunId == runId) {
          completePendingTurn(sendNext = true)
          resumeAfterPendingReply()
        }
        if (abortingRunId == runId) abortingRunId = null
      }
    }
  }

  private fun queueStreamingAssistantTts(
    text: String,
    force: Boolean,
    reason: String,
  ): Int {
    val rawSpoken = text.trim()
    val spoken = prepareStreamingAssistantTtsText(rawSpoken)
    if (spoken.isEmpty()) return 0
    if (!isAssistantTtsReady()) {
      synchronized(streamingTtsLock) {
        streamingTtsLatestText = spoken
      }
      Log.d(tag, "streaming TTS skip reason=$reason; tts not ready")
      return 0
    }

    var enqueued = 0
    synchronized(streamingTtsLock) {
      if (spoken.length < streamingTtsConsumedChars || hasStreamingTtsRewriteLocked(spoken)) {
        val active = streamingTtsWorker?.isActive == true
        Log.d(tag, "streaming TTS text rewrite detected; active=$active")
        streamingTtsQueue.clear()
        streamingTtsConsumedChars = if (active) minOf(streamingTtsConsumedChars, spoken.length) else 0
      }
      streamingTtsLatestText = spoken

      var boundary = nextStreamingTtsBoundary(spoken, streamingTtsConsumedChars, force)
      while (boundary != null) {
        val chunk = spoken.substring(streamingTtsConsumedChars, boundary).trim()
        streamingTtsConsumedChars = boundary
        while (streamingTtsConsumedChars < spoken.length && spoken[streamingTtsConsumedChars].isWhitespace()) {
          streamingTtsConsumedChars += 1
        }
        if (chunk.isNotEmpty()) {
          streamingTtsQueue.add(chunk)
          enqueued += 1
        }
        boundary = nextStreamingTtsBoundary(spoken, streamingTtsConsumedChars, force)
      }
      if (enqueued > 0) ensureStreamingTtsWorkerLocked()
    }

    if (enqueued > 0) {
      Log.d(tag, "streaming TTS enqueue reason=$reason chunks=$enqueued consumed=$streamingTtsConsumedChars chars=${spoken.length}")
    }
    return enqueued
  }

  private fun prepareStreamingAssistantTtsText(text: String): String =
    text
      .replace('\n', ' ')
      .replace('\r', ' ')
      .replace("*", "")
      .replace("#", "")
      .replace("`", "")
      .replace("  ", " ")
      .trim()

  private fun hasStreamingTtsRewriteLocked(spoken: String): Boolean {
    if (streamingTtsConsumedChars <= 0) return false
    val prefixLength = minOf(streamingTtsConsumedChars, streamingTtsLatestText.length, spoken.length)
    if (prefixLength <= 0) return false
    return spoken.substring(0, prefixLength) != streamingTtsLatestText.substring(0, prefixLength)
  }

  private fun nextStreamingTtsBoundary(
    text: String,
    start: Int,
    force: Boolean,
  ): Int? {
    if (start >= text.length) return null
    return MeloTtsTextPreprocessor.nextStreamingBoundary(text, start, force)
  }

  private fun ensureStreamingTtsWorkerLocked() {
    if (streamingTtsWorker?.isActive == true) return
    val generation = streamingTtsGeneration
    streamingTtsWorker =
      scope.launch {
        runStreamingTtsWorker(generation)
      }
  }

  private suspend fun runStreamingTtsWorker(generation: Long) {
    while (true) {
      val text =
        synchronized(streamingTtsLock) {
          if (generation != streamingTtsGeneration) {
            null
          } else if (streamingTtsQueue.isEmpty()) {
            streamingTtsWorker = null
            null
          } else {
            buildString {
              append(streamingTtsQueue.removeFirst())
              while (streamingTtsQueue.isNotEmpty()) {
                append(' ')
                append(streamingTtsQueue.removeFirst())
              }
            }
          }
        } ?: return

      try {
        Log.d(tag, "streaming TTS speak batch chars=${text.length}")
        val finished =
          withTimeoutOrNull(assistantReplyPlaybackTimeoutMs) {
            speakAssistantReply(text)
            true
          }
        if (finished != true) {
          Log.w(tag, "streaming TTS batch timed out chars=${text.length}")
        }
      } catch (err: Throwable) {
        Log.w(tag, "streaming TTS batch failed: ${err.message ?: err::class.simpleName}")
      }
    }
    synchronized(streamingTtsLock) {
      if (streamingTtsWorker?.isActive != true || generation != streamingTtsGeneration) {
        streamingTtsWorker = null
        if (streamingTtsQueue.isNotEmpty()) ensureStreamingTtsWorkerLocked()
      }
    }
  }

  private suspend fun waitForStreamingAssistantTts(timeoutMs: Long): Boolean {
    val worker = synchronized(streamingTtsLock) { streamingTtsWorker }
    return if (worker == null || !worker.isActive) {
      true
    } else {
      withTimeoutOrNull(timeoutMs) {
        worker.join()
        true
      } == true
    }
  }

  private fun hasStreamingAssistantTtsWork(): Boolean =
    synchronized(streamingTtsLock) {
      streamingTtsQueue.isNotEmpty() || streamingTtsWorker?.isActive == true
    }

  private fun hasStreamingAssistantTtsProgress(): Boolean =
    synchronized(streamingTtsLock) {
      streamingTtsConsumedChars > 0 || streamingTtsQueue.isNotEmpty() || streamingTtsWorker?.isActive == true
    }

  private fun resetStreamingAssistantTts(cancelActive: Boolean) {
    val active =
      synchronized(streamingTtsLock) {
        streamingTtsQueue.clear()
        streamingTtsConsumedChars = 0
        streamingTtsLatestText = ""
        if (cancelActive) streamingTtsGeneration += 1
        val worker = streamingTtsWorker
        worker
      }
    if (cancelActive) active?.cancel()
  }

  private fun disableMic(status: String) {
    stopRequested = true
    restartJob?.cancel()
    restartJob = null
    transcriptFlushJob?.cancel()
    transcriptFlushJob = null
    _micEnabled.value = false
    _isListening.value = false
    _inputLevel.value = 0f
    _statusText.value = status
    localCapture?.stop()
    usingLocalCapture = false
    mainHandler.post {
      recognizer?.cancel()
      recognizer?.destroy()
      recognizer = null
    }
  }

  private fun hasMicPermission(): Boolean =
    (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null

    val parts =
      content.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        if (obj["type"].asStringOrNull() != "text") return@mapNotNull null
        obj["text"].asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  private val listener =
    object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) {
        _isListening.value = true
      }

      override fun onBeginningOfSpeech() {
        interruptAssistantSpeechIfNeeded("system-speech-start")
        abortPendingReplyForUserSpeech("system-speech-start")
      }

      override fun onRmsChanged(rmsdB: Float) {
        val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        _inputLevel.value = level
      }

      override fun onBufferReceived(buffer: ByteArray?) {}

      override fun onEndOfSpeech() {
        _inputLevel.value = 0f
        scheduleRestart()
      }

      override fun onError(error: Int) {
        if (stopRequested) return
        _isListening.value = false
        _inputLevel.value = 0f
        val status =
          when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "Listening"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported on this device"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable on this device"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech requests limited; retrying"
            else -> "Speech error ($error)"
          }
        _statusText.value = status

        if (
          error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
          error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
          error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
          disableMic(status)
          return
        }

        val restartDelayMs =
          when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> 1_200L
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2_500L
            else -> 600L
          }
        scheduleRestart(delayMs = restartDelayMs)
      }

      override fun onResults(results: Bundle?) {
        transcriptFlushJob?.cancel()
        transcriptFlushJob = null
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          interruptAssistantSpeechIfNeeded("system-final")
          abortPendingReplyForUserSpeech("system-final")
          if (trimmed != flushedPartialTranscript) {
            queueRecognizedMessage(trimmed)
            sendQueuedIfIdle()
          } else {
            flushedPartialTranscript = null
            _liveTranscript.value = null
          }
        }
        scheduleRestart()
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()
        if (!text.isNullOrBlank()) {
          val trimmed = text.trim()
          interruptAssistantSpeechIfNeeded("system-partial")
          abortPendingReplyForUserSpeech("system-partial")
          _liveTranscript.value = trimmed
          scheduleTranscriptFlush(trimmed)
        }
      }

      override fun onEvent(
        eventType: Int,
        params: Bundle?,
      ) {}
    }

  private fun finishPendingTurnAfterReply(finalText: String?) {
    val targetAssistantEntryId = pendingAssistantEntryId
    val targetMessageId = targetAssistantEntryId?.hashCode() ?: 0
    scope.launch {
      try {
        val spoken = finalText?.trim().orEmpty()
        val ttsReady = isAssistantTtsReady()
        val hasStreamingTtsProgress = hasStreamingAssistantTtsProgress()
        Log.d(tag, "start final reply TTS handling: response='${spoken.take(80)}', messageId=$targetMessageId")
        Log.d(tag, "TTS controller status: $ttsReady")
        if (spoken.isNotEmpty() && !isAssistantErrorText(spoken) && (ttsReady || hasStreamingTtsProgress)) {
          if (hasStreamingTtsProgress) {
            queueStreamingAssistantTts(text = spoken, force = true, reason = "final")
            completePendingTurn(sendNext = false)
            resumeAfterPendingReply()
            Log.d(tag, "final reply waiting for streaming TTS messageId=$targetMessageId chars=${spoken.length}")
            waitForStreamingAssistantTts(assistantReplyPlaybackTimeoutMs)
          } else if (ttsReady) {
            completePendingTurn(sendNext = false)
            resumeAfterPendingReply()
            resetStreamingAssistantTts(cancelActive = true)
            Log.d(tag, "final reply speak whole messageId=$targetMessageId chars=${spoken.length}")
            speakAssistantReply(spoken)
          } else {
            completePendingTurn(sendNext = false)
            resumeAfterPendingReply()
          }
        } else {
          completePendingTurn(sendNext = false)
          resumeAfterPendingReply()
          Log.w(
            tag,
            "TTS condition not met: response=${spoken.isNotEmpty()}, responseEmpty=${spoken.isEmpty()}, ttsReady=$ttsReady, messageId=$targetMessageId",
          )
        }
      } catch (err: Throwable) {
        Log.w(tag, "assistant speech failed: ${err.message ?: err::class.simpleName}")
      } finally {
        if (finalText.isNullOrBlank()) resetStreamingAssistantTts(cancelActive = true)
        Log.d(tag, "final reply TTS handling complete messageId=$targetMessageId")
        resumeAfterPendingReply()
        resumeAfterTts()
        sendQueuedIfIdle()
      }
    }
  }

  private fun isAssistantErrorText(text: String): Boolean = text.startsWith("OpenClaw reply failed:")

  private fun finishEmptyFinalAfterHistoryLookup(reason: String) {
    val targetRunId = pendingRunId
    if (targetRunId == null) return
    if (emptyFinalLookupRunId == targetRunId) {
      Log.d(tag, "empty final history lookup already active reason=$reason runId=$targetRunId")
      return
    }
    emptyFinalLookupRunId = targetRunId
    val targetMessage = firstQueuedMessage()?.trim().orEmpty()
    scope.launch {
      val fetchedText =
        if (targetMessage.isEmpty()) {
          ""
        } else {
          withTimeoutOrNull(25_000L) {
            repeat(25) { attempt ->
              if (targetRunId != pendingRunId) return@withTimeoutOrNull null
              val text = fetchAssistantReplyForMessage(targetMessage)?.trim().orEmpty()
              if (text.isNotEmpty()) return@withTimeoutOrNull text
              if (attempt < 24) delay(1_000L)
            }
            null
          }
        }?.trim().orEmpty()

      if (targetRunId != pendingRunId) {
        Log.d(tag, "empty final history lookup ignored reason=$reason; pending run changed")
        return@launch
      }

      if (fetchedText.isNotEmpty()) {
        Log.d(tag, "empty final recovered assistant text reason=$reason chars=${fetchedText.length}")
        upsertPendingAssistant(text = fetchedText, isStreaming = false)
        finishPendingTurnAfterReply(fetchedText)
      } else {
        Log.w(tag, "empty final had no assistant text reason=$reason; completing empty voice turn runId=$targetRunId")
        finishPendingTurnAfterReply(null)
      }
    }
  }
}

private fun kotlinx.serialization.json.JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

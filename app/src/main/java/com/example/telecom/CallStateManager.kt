package com.example.telecom

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Surface
import com.example.data.AppPreferences
import com.example.model.ActiveCallSnapshot
import com.example.model.CallAudioRoute
import com.example.model.CallExtraMetadata
import com.example.model.CallLifecycleState
import com.example.model.CallNetworkBadge
import com.example.model.CallWindowPreference
import com.example.model.InCallUiState
import com.example.model.LastCallInfo
import com.example.model.RecordingMode
import com.example.model.VoiceEffectPreset
import com.example.repository.BlocklistRepository
import com.example.repository.CallLogRepository
import com.example.ui.InCallActivity
import com.example.util.AppToast
import com.example.util.CallRecorderManager
import com.example.util.DialpadToneHelper
import com.example.util.IncomingRingerManager
import com.example.util.PermissionHelper
import com.example.util.SimAccountHelper
import com.example.util.VoiceChangerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallStateManager {

  private val _uiState = MutableStateFlow(InCallUiState())
  val uiState: StateFlow<InCallUiState> = _uiState.asStateFlow()

  private var inCallService: DialerInCallService? = null
  private val trackedCalls = mutableListOf<Call>()
  private var primaryCallObj: Call? = null
  private var waitingCallObj: Call? = null

  // Per-call tracking for Missed ring time, active talk time, local reject flag, and disconnect causes
  private val ringStartMs = mutableMapOf<Int, Long>()
  private val firstObservedActiveMs = mutableMapOf<Int, Long>()
  private val wasEverActive = mutableMapOf<Int, Boolean>()
  private val activeStartMs = mutableMapOf<Int, Long>()
  private val locallyRejectedOrEnded = mutableSetOf<Int>()
  private val pendingAnsweringKeys = mutableSetOf<Int>()
  private val wasOutgoingMap = mutableMapOf<Int, Boolean>()
  private val numberOutgoingHistory = mutableMapOf<String, Boolean>()
  private val bestNetworkBadge = mutableMapOf<Int, CallNetworkBadge>()
  private val requestedVideoKeys = mutableSetOf<Int>()
  private val registeredCallbackKeys = mutableSetOf<Int>()
  private val conferenceContainerKeys = mutableSetOf<Int>()
  private val silencedWaitingCallKeys = mutableSetOf<Int>()
  private val lastCallInfoCache = mutableMapOf<Int, LastCallInfo?>()
  private val callDtmfHistory = mutableMapOf<Int, String>()
  private var activeDtmfCall: Call? = null
  private var pendingDtmfStartRunnable: Runnable? = null
  private var pendingDtmfStopRunnable: Runnable? = null
  @Volatile
  var isInCallUiVisible: Boolean = false
    private set
  private var lastPostedWaitingNotifKey: String? = null
  private var pendingSplitCallKey: Int? = null
  private var pendingSplitUntilMs: Long = 0L
  @Volatile
  private var lastPlacedVideoNormalizedNumber: String? = null
  @Volatile
  private var lastPlacedVideoTimestampMs: Long = 0L
  @Volatile
  private var lastPlacedOutgoingNumber: String? = null
  @Volatile
  private var lastPlacedOutgoingMs: Long = 0L
  @Volatile
  private var lastMissedNotifiedNumber: String? = null
  @Volatile
  private var lastMissedNotifiedMs: Long = 0L

  fun wasRecentlyNotifiedMissed(number: String): Boolean {
    val norm = CallLogRepository.normalizeNumber(number)
    if (norm.isEmpty()) return false
    return norm == lastMissedNotifiedNumber && (System.currentTimeMillis() - lastMissedNotifiedMs) <= 10_000L
  }

  private val handler = Handler(Looper.getMainLooper())
  private var timerRunning = false
  private var recordingStartMs = 0L

  private var previewSurfaceRef: Surface? = null
  private var displaySurfaceRef: Surface? = null
  private var usingFrontCamera = true
  private var cameraEnabled = true

  private fun ensureTimerRunning() {
    if (!timerRunning && trackedCalls.isNotEmpty()) {
      timerRunning = true
      handler.removeCallbacks(timerRunnable)
      handler.post(timerRunnable)
    }
  }

  fun resolveConfirmedConnectTimestamp(
    detailsConnectMs: Long,
    firstActiveObservedMs: Long?,
    nowMs: Long,
    wasAlreadyActiveOnAdd: Boolean = false
  ): Long {
    if (detailsConnectMs > 0L) {
      if (wasAlreadyActiveOnAdd) return detailsConnectMs
      if (firstActiveObservedMs != null && detailsConnectMs < firstActiveObservedMs - 1_500L) {
        return firstActiveObservedMs
      }
      return detailsConnectMs
    }
    if (firstActiveObservedMs != null && (nowMs - firstActiveObservedMs) >= 3_500L) {
      return firstActiveObservedMs + 2_500L
    }
    return 0L
  }

  private fun resolveActiveConnectTimeMillis(call: Call?): Long {
    if (call == null) return 0L
    val st = mapState(call)
    if (st != CallLifecycleState.ACTIVE && st != CallLifecycleState.HOLDING) return 0L
    val key = call.hashCode()
    activeStartMs[key]?.let { return it }

    val nowMs = System.currentTimeMillis()
    val detailsConnect = call.details?.connectTimeMillis ?: 0L
    val firstActive = firstObservedActiveMs[key]
    val confirmed = resolveConfirmedConnectTimestamp(
      detailsConnectMs = detailsConnect,
      firstActiveObservedMs = firstActive,
      nowMs = nowMs,
      wasAlreadyActiveOnAdd = firstActive == null && detailsConnect > 0L
    )
    if (confirmed > 0L) {
      activeStartMs[key] = confirmed
      wasEverActive[key] = true
      pendingAnsweringKeys.remove(key)
      return confirmed
    }

    if (isConferenceCall(call)) {
      val inheritedChildStart = call.children.mapNotNull { child ->
        val childKey = child.hashCode()
        activeStartMs[childKey] ?: child.details?.connectTimeMillis?.takeIf { it > 0L }
      }.minOrNull()
      if (inheritedChildStart != null && inheritedChildStart > 0L) {
        activeStartMs[key] = inheritedChildStart
        wasEverActive[key] = true
        return inheritedChildStart
      }
    }

    return 0L
  }

  private fun computeElapsedSeconds(call: Call?): Long {
    val startMs = resolveActiveConnectTimeMillis(call)
    if (startMs <= 0L) return 0L
    return ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
  }

  private fun computeWaitingElapsedSeconds(call: Call?): Long {
    if (call == null) return 0L
    if (mapState(call) != CallLifecycleState.RINGING) return 0L
    val key = call.hashCode()
    val startMs = ringStartMs[key] ?: System.currentTimeMillis().also { ringStartMs[key] = it }
    return ((System.currentTimeMillis() - startMs) / 1000L).coerceAtLeast(0L)
  }

  private val timerRunnable = object : Runnable {
    override fun run() {
      val elapsed = computeElapsedSeconds(primaryCallObj)
      val secondaryElapsed = computeElapsedSeconds(waitingCallObj)
      val waitingElapsed = computeWaitingElapsedSeconds(waitingCallObj)
      val primaryConnectMs = resolveActiveConnectTimeMillis(primaryCallObj)

      val recSec = if (_uiState.value.isRecording && recordingStartMs > 0L) {
        ((System.currentTimeMillis() - recordingStartMs) / 1000L).coerceAtLeast(0L)
      } else {
        0L
      }

      _uiState.value = _uiState.value.copy(
        elapsedSeconds = elapsed,
        secondaryElapsedSeconds = secondaryElapsed,
        primaryConnectTimeMillis = primaryConnectMs,
        waitingElapsedSeconds = waitingElapsed,
        recordingSeconds = recSec
      )

      if (trackedCalls.isNotEmpty()) {
        handler.postDelayed(this, 500L)
      } else {
        timerRunning = false
      }
    }
  }

  private val callCallback = object : Call.Callback() {
    override fun onStateChanged(call: Call, state: Int) {
      val key = call.hashCode()
      if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_SELECT_PHONE_ACCOUNT) {
        wasOutgoingMap[key] = true
        val num = call.details?.handle?.schemeSpecificPart.orEmpty()
        val norm = CallLogRepository.normalizeNumber(num)
        if (norm.isNotEmpty()) numberOutgoingHistory[norm] = true
      } else if (state == Call.STATE_RINGING) {
        wasOutgoingMap[key] = false
        if (!ringStartMs.containsKey(key)) {
          ringStartMs[key] = System.currentTimeMillis()
        }
        val num = call.details?.handle?.schemeSpecificPart.orEmpty()
        val norm = CallLogRepository.normalizeNumber(num)
        if (norm.isNotEmpty()) numberOutgoingHistory[norm] = false
      } else if (state == Call.STATE_ACTIVE) {
        if (!firstObservedActiveMs.containsKey(key)) {
          firstObservedActiveMs[key] = System.currentTimeMillis()
          // Fallback sync after 3.6s only if carrier Telecom never populates connectTimeMillis
          handler.postDelayed({
            inCallService?.let { svc ->
              if (trackedCalls.contains(call) && mapState(call) == CallLifecycleState.ACTIVE) {
                resolveActiveConnectTimeMillis(call)
                syncState(svc)
              }
            }
          }, 3_600L)
        }
        resolveActiveConnectTimeMillis(call)
      }

      inCallService?.let { service ->
        if (trackedCalls.none { mapState(it) == CallLifecycleState.RINGING }) {
          IncomingRingerManager.stopRinging(service)
        }
        if (state == Call.STATE_ACTIVE) {
          val isAudioOnlyNow = !android.telecom.VideoProfile.isVideo(call.details?.videoState ?: 0)
          if (requestedVideoKeys.remove(key) && isAudioOnlyNow) {
            markVideoCallFailed(service)
          }
        }
        if (isConferenceCall(call)) {
          conferenceContainerKeys.add(key)
        }
        val shouldAutoOrPendingRec = _uiState.value.isRecordingPending ||
          AppPreferences.settings.value.recordingMode == RecordingMode.AUTO_ALL
        if (state == Call.STATE_ACTIVE && resolveActiveConnectTimeMillis(call) > 0L && shouldAutoOrPendingRec && !_uiState.value.isRecording) {
          val snap = toSnapshot(service, call)
          startRecordingInternal(service, snap.number, snap.displayName)
        }
        if (state == Call.STATE_DISCONNECTED) {
          handleCallDisconnectTransition(service, call)
        }
        syncState(service)
        ensureTimerRunning()
      }
    }

    override fun onDetailsChanged(call: Call, details: Call.Details) {
      inCallService?.let { service ->
        val key = call.hashCode()
        if (isConferenceCall(call)) {
          conferenceContainerKeys.add(key)
        }
        val st = mapState(call)
        if ((st == CallLifecycleState.ACTIVE || st == CallLifecycleState.HOLDING) && details.connectTimeMillis > 0L) {
          resolveActiveConnectTimeMillis(call)
        }
        val isNowVideo = VideoProfile.isVideo(details.videoState)
        if (st == CallLifecycleState.ACTIVE && requestedVideoKeys.contains(key) && !isNowVideo) {
          requestedVideoKeys.remove(key)
          markVideoCallFailed(service)
        }
        syncState(service)
      }
    }

    override fun onVideoCallChanged(call: Call, videoCall: android.telecom.InCallService.VideoCall?) {
      videoCall?.registerCallback(videoCallCallback)
      inCallService?.let { service ->
        syncState(service)
        applyVideoSurfaces()
      }
    }

    override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
      ensureCallTracked(call)
      if (children.isNotEmpty() || isConferenceCall(call)) {
        conferenceContainerKeys.add(call.hashCode())
      }
      children.forEach { ensureCallTracked(it) }
      inCallService?.let { service -> syncState(service) }
    }

    override fun onParentChanged(call: Call, parent: Call?) {
      ensureCallTracked(call)
      if (parent != null) {
        ensureCallTracked(parent)
        if (isConferenceCall(parent)) {
          conferenceContainerKeys.add(parent.hashCode())
        }
      }
      inCallService?.let { service -> syncState(service) }
    }

    override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
      inCallService?.let { service -> syncState(service) }
    }
  }

  private val videoCallCallback = object : android.telecom.InCallService.VideoCall.Callback() {
    override fun onSessionModifyRequestReceived(videoProfile: VideoProfile?) {}

    override fun onSessionModifyResponseReceived(
      status: Int,
      requestedProfile: VideoProfile?,
      responseProfile: VideoProfile?
    ) {
      // Connection.VideoProvider.SESSION_MODIFY_STATUS_SUCCESS == 1 in Android Telecom
      val failed = status != 1 ||
        (requestedProfile != null && VideoProfile.isVideo(requestedProfile.videoState) &&
          (responseProfile == null || !VideoProfile.isVideo(responseProfile.videoState)))
      if (failed) {
        inCallService?.let { service ->
          markVideoCallFailed(service)
          syncState(service)
        }
      }
    }

    override fun onCallSessionEvent(event: Int) {}
    override fun onPeerDimensionsChanged(width: Int, height: Int) {}
    override fun onVideoQualityChanged(videoQuality: Int) {}
    override fun onCallDataUsageChanged(dataUsage: Long) {}
    override fun onCameraCapabilitiesChanged(cameraCapabilities: android.telecom.VideoProfile.CameraCapabilities?) {}
  }

  private fun markVideoCallFailed(context: Context) {
    _uiState.value = _uiState.value.copy(videoFailureMessage = "Video call not successful")
    handler.post {
      AppToast.show(context, "Video call not successful")
    }
  }

  private fun isConferenceCall(call: Call): Boolean {
    val props = call.details?.callProperties ?: 0
    return (props and Call.Details.PROPERTY_CONFERENCE) != 0 || call.children.isNotEmpty()
  }

  private fun ensureCallTracked(call: Call) {
    val key = call.hashCode()
    if (registeredCallbackKeys.add(key)) {
      call.registerCallback(callCallback)
      call.videoCall?.registerCallback(videoCallCallback)
    }
    if (!trackedCalls.contains(call)) {
      trackedCalls.add(call)
    }
    if (isConferenceCall(call)) {
      conferenceContainerKeys.add(key)
      call.children.forEach { child ->
        if (child != call) ensureCallTracked(child)
      }
    }
  }

  fun attachService(service: DialerInCallService) {
    inCallService = service
  }

  fun detachService(context: Context) {
    inCallService = null
    trackedCalls.forEach { runCatching { it.unregisterCallback(callCallback) } }
    trackedCalls.clear()
    registeredCallbackKeys.clear()
    conferenceContainerKeys.clear()
    silencedWaitingCallKeys.clear()
    lastCallInfoCache.clear()
    callDtmfHistory.clear()
    pendingDtmfStartRunnable?.let { handler.removeCallbacks(it) }
    pendingDtmfStopRunnable?.let { handler.removeCallbacks(it) }
    pendingDtmfStartRunnable = null
    pendingDtmfStopRunnable = null
    activeDtmfCall = null
    isInCallUiVisible = false
    lastPostedWaitingNotifKey = null
    pendingSplitCallKey = null
    pendingSplitUntilMs = 0L
    primaryCallObj = null
    waitingCallObj = null
    ringStartMs.clear()
    firstObservedActiveMs.clear()
    wasEverActive.clear()
    activeStartMs.clear()
    locallyRejectedOrEnded.clear()
    pendingAnsweringKeys.clear()
    wasOutgoingMap.clear()
    numberOutgoingHistory.clear()
    bestNetworkBadge.clear()
    requestedVideoKeys.clear()

    if (_uiState.value.isRecording) {
      val (_, recSec) = CallRecorderManager.stopRecording(context)
      if (recSec > 0L) {
        AppToast.show(context, "Recording saved ${formatDuration(recSec)}sec")
      }
    }
    IncomingRingerManager.stopRinging(context)
    VoiceChangerManager.stop(context)
    WaitingCallNotificationManager.cancelIncomingNotification(context)
    WaitingCallNotificationManager.cancelWaitingNotification(context)
    FloatingCallOverlayService.stop(context)
    _uiState.value = InCallUiState()
  }

  private fun isCallOutgoing(call: Call, number: String): Boolean {
    val key = call.hashCode()
    wasOutgoingMap[key]?.let { return it }

    // Never trust Telecom's synthetic DIRECTION_INCOMING (0) blindly for a Conference call!
    if (isConferenceCall(call)) {
      val (_, hasOut, _) = resolveConferenceDirectionFlags(call)
      return hasOut
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val dir = call.details?.callDirection
      if (dir == Call.Details.DIRECTION_OUTGOING) return true
      if (dir == Call.Details.DIRECTION_INCOMING) {
        // Double check recently tracked number history in case this is a new IMS child handle
        val norm = CallLogRepository.normalizeNumber(number)
        if (norm.isNotEmpty()) {
          numberOutgoingHistory[norm]?.let { return it }
        }
        return false
      }
    }
    val rawState = @Suppress("DEPRECATION") call.state
    if (rawState == Call.STATE_DIALING || rawState == Call.STATE_CONNECTING || rawState == Call.STATE_SELECT_PHONE_ACCOUNT) {
      return true
    }
    val norm = CallLogRepository.normalizeNumber(number)
    if (norm.isNotEmpty()) {
      numberOutgoingHistory[norm]?.let { return it }
      if (norm == lastPlacedOutgoingNumber && System.currentTimeMillis() - lastPlacedOutgoingMs <= 12_000L) {
        return true
      }
    }
    return rawState != Call.STATE_RINGING
  }

  /**
   * Resolves Triple(hasIncoming, hasOutgoing, isMixed) across conference children and tracked calls.
   */
  private fun resolveConferenceDirectionFlags(conferenceCall: Call): Triple<Boolean, Boolean, Boolean> {
    var hasIncoming = false
    var hasOutgoing = false
    val liveChildren = conferenceCall.children.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    for (child in liveChildren) {
      val childKey = child.hashCode()
      val cNum = child.details?.handle?.schemeSpecificPart.orEmpty()
      val cNorm = CallLogRepository.normalizeNumber(cNum)
      val outFlag = when {
        wasOutgoingMap.containsKey(childKey) -> wasOutgoingMap[childKey]!!
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
          child.details?.callDirection == Call.Details.DIRECTION_OUTGOING -> true
        cNorm.isNotEmpty() && numberOutgoingHistory.containsKey(cNorm) -> numberOutgoingHistory[cNorm]!!
        cNorm.isNotEmpty() && cNorm == lastPlacedOutgoingNumber &&
          System.currentTimeMillis() - lastPlacedOutgoingMs <= 30_000L -> true
        else -> {
          // If another tracked standalone call matches the same number, use its direction
          val matchingTracked = trackedCalls.firstOrNull {
            it != child && it != conferenceCall &&
              CallLogRepository.normalizeNumber(it.details?.handle?.schemeSpecificPart.orEmpty()) == cNorm &&
              wasOutgoingMap.containsKey(it.hashCode())
          }
          matchingTracked?.let { wasOutgoingMap[it.hashCode()] } ?: false
        }
      }
      wasOutgoingMap[childKey] = outFlag
      if (outFlag) hasOutgoing = true else hasIncoming = true
    }

    if (!hasIncoming && !hasOutgoing) {
      // Fallback when carrier temporarily reports empty children on initial Conference creation
      val priorTracked = trackedCalls.filter { it != conferenceCall && !isConferenceCall(it) }
      priorTracked.forEach { pc ->
        val outFlag = wasOutgoingMap[pc.hashCode()]
        if (outFlag == true) hasOutgoing = true else if (outFlag == false) hasIncoming = true
      }
      if (!hasIncoming && !hasOutgoing) {
        hasOutgoing = true
      }
    }
    return Triple(hasIncoming, hasOutgoing, hasIncoming && hasOutgoing)
  }

  fun onCallAdded(service: DialerInCallService, call: Call) {
    val number = call.details?.handle?.schemeSpecificPart.orEmpty()
    // Let OS Phone handle dialable MMI / USSD sessions without covering them with custom InCall UI
    if (!isConferenceCall(call) && com.example.util.SecretCodeHandler.isMmiOrUssdCode(number)) {
      return
    }
    val state = mapState(call)
    val key = call.hashCode()
    val detectedOutgoing = isCallOutgoing(call, number)

    // Auto-reject if blocked incoming call (works as extra guard alongside CallScreeningService)
    if (!isConferenceCall(call) && !detectedOutgoing && state == CallLifecycleState.RINGING && BlocklistRepository.isBlocked(service, number)) {
      locallyRejectedOrEnded.add(key)
      WaitingCallNotificationManager.showBlockedCallNotification(service, number)
      runCatching { call.reject(false, null) }
      return
    }

    if (!isConferenceCall(call)) {
      WaitingCallNotificationManager.markBlockedNotificationsSeenOrReset(service)
      wasOutgoingMap[key] = detectedOutgoing
      val norm = CallLogRepository.normalizeNumber(number)
      if (norm.isNotEmpty()) {
        numberOutgoingHistory[norm] = detectedOutgoing
      }
    } else {
      conferenceContainerKeys.add(key)
    }
    if (!detectedOutgoing && state == CallLifecycleState.RINGING) {
      ringStartMs[key] = System.currentTimeMillis()
    } else if (state == CallLifecycleState.ACTIVE) {
      if ((call.details?.connectTimeMillis ?: 0L) > 0L) {
        wasEverActive[key] = true
        activeStartMs[key] = call.details!!.connectTimeMillis
      } else {
        firstObservedActiveMs[key] = System.currentTimeMillis()
      }
    }

    ensureCallTracked(call)
    val normAdded = CallLogRepository.normalizeNumber(number)
    if (VideoProfile.isVideo(call.details?.videoState ?: 0) ||
      (normAdded.isNotEmpty() && normAdded == lastPlacedVideoNormalizedNumber &&
        System.currentTimeMillis() - lastPlacedVideoTimestampMs <= 12_000L)
    ) {
      requestedVideoKeys.add(key)
      lastPlacedVideoNormalizedNumber = null
    }

    if (AppPreferences.settings.value.recordingMode == RecordingMode.AUTO_ALL && !_uiState.value.isRecording) {
      if (state == CallLifecycleState.ACTIVE) {
        val snap = toSnapshot(service, call)
        startRecordingInternal(service, snap.number, snap.displayName)
      } else {
        _uiState.value = _uiState.value.copy(isRecordingPending = true)
      }
    }

    syncState(service)
    ensureTimerRunning()

    val settings = AppPreferences.settings.value
    if (settings.disableCustomInCallUi) {
      return
    }

    // Strictly distinguish Waiting INCOMING call from a 2nd OUTGOING call!
    val isWaitingIncomingScenario = trackedCalls.size > 1 && !detectedOutgoing && state == CallLifecycleState.RINGING
    val primarySnap = primaryCallObj?.let { toSnapshot(service, it) }
    val waitingSnap = waitingCallObj?.let { toSnapshot(service, it) }

    if (isWaitingIncomingScenario && waitingSnap != null) {
      FloatingCallOverlayService.start(service)
    } else if (primarySnap != null) {
      val isPrimaryIncoming = primarySnap.state == CallLifecycleState.RINGING && primarySnap.isIncoming
      if (isPrimaryIncoming && !silencedWaitingCallKeys.contains(primaryCallObj?.hashCode() ?: 0)) {
        val simDetails = primaryCallObj?.let { SimAccountHelper.resolveSimDetails(service, it.details?.accountHandle?.id) }
        val simSlotIdx = simDetails?.let { if (it.slotNumber > 0) it.slotNumber - 1 else null }
        IncomingRingerManager.startRinging(service, simSlotIdx)
      }

      val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val isScreenLocked = keyguard?.isKeyguardLocked == true

      // Rules:
      // 1) Floating XOS mode (when unlocked & overlay permission): ONLY XOS Floating Window (no heads-up pop-up).
      // 2) Notification Call mode (when unlocked): Normal heads-up pop-up notification ONLY (no floating, no auto full-screen).
      // 3) Full Screen HyperOS mode OR locked screen: Full-Screen HyperOS InCall UI (no heads-up pop-up).
      // 4) Outgoing calls ALWAYS use Full Screen InCall UI.
      val useFloating = isPrimaryIncoming &&
        !isScreenLocked &&
        settings.incomingWindowMode == CallWindowPreference.FLOATING_XOS &&
        PermissionHelper.hasOverlayPermission(service)

      val useNotificationPopup = isPrimaryIncoming &&
        !isScreenLocked &&
        settings.incomingWindowMode == CallWindowPreference.NOTIFICATION_CALL

      WaitingCallNotificationManager.cancelIncomingNotification(service)
      if (useFloating) {
        _uiState.value = _uiState.value.copy(isInSmallFloatingMode = true)
        FloatingCallOverlayService.start(service)
      } else if (useNotificationPopup) {
        _uiState.value = _uiState.value.copy(isInSmallFloatingMode = false)
        FloatingCallOverlayService.start(service)
      } else {
        _uiState.value = _uiState.value.copy(isInSmallFloatingMode = false)
        FloatingCallOverlayService.start(service)
        launchFullScreenInCallUi(service)
      }
    }
  }

  private fun handleCallDisconnectTransition(context: Context, call: Call) {
    val key = call.hashCode()
    if (!handledDisconnectKeys.add(key)) return

    // If a Conference container dissolved because participants were split back into standalone calls,
    // do NOT emit a spurious "Call ended" toast or log disconnect while its live calls continue.
    val isConfContainer = conferenceContainerKeys.contains(key) || isConferenceCall(call)
    if (isConfContainer) {
      call.children.forEach { ensureCallTracked(it) }
      val remainingLiveCalls = trackedCalls.any {
        val st = mapState(it)
        it != call &&
          (it.parent == null || it.parent == call) &&
          st != CallLifecycleState.DISCONNECTED &&
          st != CallLifecycleState.DISCONNECTING
      }
      if (remainingLiveCalls) {
        return
      }
    }

    val snap = toSnapshot(context, call)
    val cause = call.details?.disconnectCause
    val reasonText = resolveDisconnectReason(cause)
    val ringStart = ringStartMs[key]
    val activeStart = activeStartMs[key] ?: call.details?.connectTimeMillis?.takeIf { it > 0L }
    val everActive = wasEverActive[key] == true || (call.details?.connectTimeMillis ?: 0L) > 0L

    // 1. Check Missed Call (incoming call never active & not locally rejected)
    val isMissed = !everActive &&
      wasOutgoingMap[key] == false &&
      !locallyRejectedOrEnded.contains(key) &&
      (cause == null || cause.code == DisconnectCause.MISSED || cause.code == DisconnectCause.CANCELED)

    val ringSec = if (ringStart != null) {
      ((System.currentTimeMillis() - ringStart) / 1000L).coerceAtLeast(1L)
    } else 0L

    // Only store disconnect reason on unanswered OUTGOING calls;
    // never attach "Incoming rejected" or disconnect status to incoming/rejected/missed/talked calls.
    val storedReason = if (!everActive && wasOutgoingMap[key] == true) {
      reasonText
    } else {
      null
    }

    // Persist extra call metadata (ring duration, disconnect reason only if unanswered, network quality badge)
    val normNum = CallLogRepository.normalizeNumber(snap.number)
    if (normNum.isNotEmpty()) {
      AppPreferences.saveCallExtraMetadata(
        context,
        CallExtraMetadata(
          normalizedNumber = normNum,
          timestampMillis = System.currentTimeMillis(),
          ringDurationSeconds = if (isMissed) ringSec else 0L,
          disconnectReason = storedReason,
          networkBadge = bestNetworkBadge[key] ?: snap.networkBadge
        )
      )
    }

    if (isMissed) {
      lastMissedNotifiedNumber = normNum
      lastMissedNotifiedMs = System.currentTimeMillis()
      WaitingCallNotificationManager.showMissedCallNotification(
        context = context,
        callerName = snap.displayName,
        number = snap.number,
        ringDurationSeconds = ringSec,
        photoUri = snap.photoUri
      )
    }

    // 2. Call Ended Toast directly ("Call ended 00:xx" if talked, or "Call ended" if cut locally/normal without error)
    if (everActive) {
      val talkedSec = if (activeStart != null && activeStart > 0L) {
        ((System.currentTimeMillis() - activeStart) / 1000L).coerceAtLeast(0L)
      } else {
        _uiState.value.elapsedSeconds
      }
      AppToast.show(context, "Call ended ${formatDuration(talkedSec)}")
    } else if (reasonText != null && wasOutgoingMap[key] == true) {
      // Actual operator error on unanswered outgoing call -> brief InCall banner + Recents reason
      _uiState.value = _uiState.value.copy(disconnectBannerMessage = reasonText)
    } else {
      AppToast.show(context, "Call ended")
    }
  }

  fun onCallRemoved(service: DialerInCallService, call: Call) {
    val key = call.hashCode()
    if (!trackedCalls.contains(call)) {
      locallyRejectedOrEnded.remove(key)
      handledDisconnectKeys.remove(key)
      return
    }
    // Preserve any children of a dissolving conference before disconnect evaluation
    call.children.forEach { child ->
      if (child != call) ensureCallTracked(child)
    }
    // Ensure disconnect handling ran even if Telecom jumps straight to onCallRemoved
    handleCallDisconnectTransition(service, call)

    runCatching { call.unregisterCallback(callCallback) }
    trackedCalls.remove(call)
    registeredCallbackKeys.remove(key)
    conferenceContainerKeys.remove(key)
    if (pendingSplitCallKey == key) {
      pendingSplitCallKey = null
    }
    ringStartMs.remove(key)
    firstObservedActiveMs.remove(key)
    wasEverActive.remove(key)
    activeStartMs.remove(key)
    locallyRejectedOrEnded.remove(key)
    pendingAnsweringKeys.remove(key)
    silencedWaitingCallKeys.remove(key)
    lastCallInfoCache.remove(key)
    callDtmfHistory.remove(key)
    if (activeDtmfCall == call) {
      pendingDtmfStartRunnable?.let { handler.removeCallbacks(it) }
      pendingDtmfStopRunnable?.let { handler.removeCallbacks(it) }
      pendingDtmfStartRunnable = null
      pendingDtmfStopRunnable = null
      activeDtmfCall = null
    }
    wasOutgoingMap.remove(key)
    bestNetworkBadge.remove(key)
    requestedVideoKeys.remove(key)

    if (call == waitingCallObj) {
      lastPostedWaitingNotifKey = null
      WaitingCallNotificationManager.cancelWaitingNotification(service)
    }

    if (trackedCalls.isEmpty()) {
      if (_uiState.value.isRecording) {
        val (_, recSec) = CallRecorderManager.stopRecording(service)
        if (recSec > 0L) {
          AppToast.show(service, "Recording saved ${formatDuration(recSec)}sec")
        }
      }
      IncomingRingerManager.stopRinging(service)
      IncomingRingerManager.stopCallWaitingAlert(service)
      VoiceChangerManager.stop(service)
      WaitingCallNotificationManager.cancelIncomingNotification(service)
      WaitingCallNotificationManager.cancelWaitingNotification(service)
      FloatingCallOverlayService.stop(service)

      val banner = _uiState.value.disconnectBannerMessage
      if (!banner.isNullOrBlank()) {
        // Hold the InCall UI briefly (~2s) to show operator disconnect reason
        handler.postDelayed({
          if (trackedCalls.isEmpty()) {
            _uiState.value = InCallUiState()
            handledDisconnectKeys.clear()
          }
        }, 2000L)
      } else {
        _uiState.value = InCallUiState()
        handledDisconnectKeys.clear()
      }
    } else {
      if (trackedCalls.none { mapState(it) == CallLifecycleState.RINGING }) {
        IncomingRingerManager.stopRinging(service)
        IncomingRingerManager.stopCallWaitingAlert(service)
      }
      syncState(service)
      ensureTimerRunning()
    }
  }

  private val handledDisconnectKeys = mutableSetOf<Int>()

  fun onCallAudioStateChanged(audioState: CallAudioState) {
    val route = when (audioState.route) {
      CallAudioState.ROUTE_SPEAKER -> CallAudioRoute.SPEAKER
      CallAudioState.ROUTE_BLUETOOTH -> CallAudioRoute.BLUETOOTH
      CallAudioState.ROUTE_WIRED_HEADSET -> CallAudioRoute.WIRED_HEADSET
      else -> CallAudioRoute.EARPIECE
    }

    val supported = mutableSetOf<CallAudioRoute>()
    val mask = audioState.supportedRouteMask
    if ((mask and CallAudioState.ROUTE_EARPIECE) != 0) supported.add(CallAudioRoute.EARPIECE)
    if ((mask and CallAudioState.ROUTE_SPEAKER) != 0) supported.add(CallAudioRoute.SPEAKER)
    if ((mask and CallAudioState.ROUTE_BLUETOOTH) != 0) supported.add(CallAudioRoute.BLUETOOTH)
    if ((mask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) supported.add(CallAudioRoute.WIRED_HEADSET)

    _uiState.value = _uiState.value.copy(
      isMuted = audioState.isMuted,
      audioRoute = route,
      supportedAudioRoutes = supported.ifEmpty { setOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER) }
    )
  }

  fun syncState(context: Context) {
    // Ensure conference children are always registered & tracked
    for (c in trackedCalls.toList()) {
      c.children.forEach { child ->
        if (child != c) ensureCallTracked(child)
      }
    }

    if (pendingSplitCallKey != null && System.currentTimeMillis() > pendingSplitUntilMs) {
      pendingSplitCallKey = null
    }

    // Live calls only (ignore DISCONNECTED / DISCONNECTING conference containers so split targets appear cleanly)
    val liveCalls = trackedCalls.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    val liveKeys = liveCalls.map { it.hashCode() }.toSet()

    // Top-level calls only (treat children whose parent Conference is gone/disconnecting as top-level immediately)
    val topLevelCalls = liveCalls.filter {
      val p = it.parent
      p == null || !liveKeys.contains(p.hashCode())
    }.ifEmpty { liveCalls }

    val ringingCalls = topLevelCalls.filter {
      mapState(it) == CallLifecycleState.RINGING && !isConferenceCall(it) && wasOutgoingMap[it.hashCode()] != true
    }
    val nonRinging = topLevelCalls.filter { !ringingCalls.contains(it) }

    val primary: Call?
    val waiting: Call?
    if (nonRinging.isNotEmpty()) {
      val splitTarget = pendingSplitCallKey?.let { splitKey ->
        nonRinging.firstOrNull { it.hashCode() == splitKey }
      }
      // Newly placed 2nd outgoing call (DIALING/CONNECTING), split active call, or ACTIVE call takes primary priority over HOLDING
      primary = nonRinging.firstOrNull {
        val st = mapState(it)
        st == CallLifecycleState.DIALING || st == CallLifecycleState.CONNECTING
      } ?: splitTarget?.takeIf { mapState(it) == CallLifecycleState.ACTIVE }
        ?: nonRinging.firstOrNull { mapState(it) == CallLifecycleState.ACTIVE }
        ?: splitTarget
        ?: nonRinging.first()
      waiting = ringingCalls.firstOrNull() ?: nonRinging.firstOrNull { it != primary }
    } else {
      primary = ringingCalls.firstOrNull()
      waiting = ringingCalls.drop(1).firstOrNull()
    }

    primaryCallObj = primary
    waitingCallObj = waiting

    // Check if an active call is truly connected before starting pending/auto call recording
    val activeForRec = nonRinging.firstOrNull {
      mapState(it) == CallLifecycleState.ACTIVE && resolveActiveConnectTimeMillis(it) > 0L
    }
    val shouldStartRec = _uiState.value.isRecordingPending ||
      AppPreferences.settings.value.recordingMode == RecordingMode.AUTO_ALL
    if (activeForRec != null && shouldStartRec && !_uiState.value.isRecording) {
      val recSnap = toSnapshot(context, activeForRec)
      startRecordingInternal(context, recSnap.number, recSnap.displayName)
    }

    val primarySnap = primary?.let { toSnapshot(context, it) }
    val waitingSnap = waiting?.let { toSnapshot(context, it) }

    val hasOngoingNonRinging = nonRinging.isNotEmpty()
    val hasWaitingRinging = hasOngoingNonRinging && waitingSnap?.state == CallLifecycleState.RINGING
    val hasSoloPrimaryRinging = !hasOngoingNonRinging &&
      primarySnap?.state == CallLifecycleState.RINGING &&
      primarySnap.isIncoming
    val isWaitingSilenced = hasWaitingRinging && waiting != null && silencedWaitingCallKeys.contains(waiting.hashCode())
    val isPrimarySilenced = hasSoloPrimaryRinging && primary != null && silencedWaitingCallKeys.contains(primary.hashCode())

    if (hasWaitingRinging && waitingSnap != null) {
      // Never play full ringtone while an ongoing call is active; play configured Call Waiting tut-tut/350ms vibrate
      IncomingRingerManager.stopRinging(context)
      if (isWaitingSilenced) {
        IncomingRingerManager.stopCallWaitingAlert(context)
      } else {
        IncomingRingerManager.startCallWaitingAlert(
          context = context,
          waitingCallKey = waitingSnap.callId,
          mode = AppPreferences.settings.value.callWaitingAlertMode
        )
      }
      WaitingCallNotificationManager.cancelIncomingNotification(context)
      val settingsNow = AppPreferences.settings.value
      val shouldPostWaitingNotification = !settingsNow.disableCustomInCallUi &&
        settingsNow.incomingWindowMode == CallWindowPreference.NOTIFICATION_CALL
      if (shouldPostWaitingNotification) {
        if (lastPostedWaitingNotifKey != waitingSnap.callId) {
          lastPostedWaitingNotifKey = waitingSnap.callId
          WaitingCallNotificationManager.showWaitingCallNotification(
            context = context,
            waitingCall = waitingSnap,
            activeCallName = primarySnap?.displayName ?: "Active Call"
          )
        }
      } else {
        lastPostedWaitingNotifKey = null
        WaitingCallNotificationManager.cancelWaitingNotification(context)
      }
    } else if (hasSoloPrimaryRinging) {
      // Ongoing call ended while waiting call is still ringing -> switch from waiting alert to device-setting ring/vibrate!
      val wasWaitingBefore = _uiState.value.waitingCall?.callId == primarySnap?.callId
      lastPostedWaitingNotifKey = null
      IncomingRingerManager.stopCallWaitingAlert(context)
      WaitingCallNotificationManager.cancelWaitingNotification(context)
      if (!isPrimarySilenced) {
        val simDetails = primary?.let { SimAccountHelper.resolveSimDetails(context, it.details?.accountHandle?.id) }
        val simSlotIdx = simDetails?.let { if (it.slotNumber > 0) it.slotNumber - 1 else null }
        IncomingRingerManager.startRinging(context, simSlotIdx)
      }
      val settingsNow = AppPreferences.settings.value
      if (wasWaitingBefore && !settingsNow.disableCustomInCallUi) {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isScreenLocked = keyguard?.isKeyguardLocked == true
        val useFloating = !isScreenLocked &&
          settingsNow.incomingWindowMode == CallWindowPreference.FLOATING_XOS &&
          PermissionHelper.hasOverlayPermission(context)
        val useNotifPopup = !isScreenLocked &&
          settingsNow.incomingWindowMode == CallWindowPreference.NOTIFICATION_CALL
        if (useFloating) {
          _uiState.value = _uiState.value.copy(isInSmallFloatingMode = true)
          FloatingCallOverlayService.start(context)
        } else if (useNotifPopup) {
          _uiState.value = _uiState.value.copy(isInSmallFloatingMode = false)
          FloatingCallOverlayService.start(context)
        } else {
          _uiState.value = _uiState.value.copy(isInSmallFloatingMode = false)
          FloatingCallOverlayService.start(context)
          launchFullScreenInCallUi(context)
        }
      }
    } else {
      lastPostedWaitingNotifKey = null
      IncomingRingerManager.stopRinging(context)
      IncomingRingerManager.stopCallWaitingAlert(context)
    }

    if (primarySnap?.state != CallLifecycleState.RINGING) {
      WaitingCallNotificationManager.cancelIncomingNotification(context)
    }
    if (waitingSnap?.state != CallLifecycleState.RINGING) {
      lastPostedWaitingNotifKey = null
      WaitingCallNotificationManager.cancelWaitingNotification(context)
    }

    val pendingRecStillWaiting = _uiState.value.isRecordingPending &&
      !_uiState.value.isRecording &&
      activeForRec == null &&
      trackedCalls.isNotEmpty()

    _uiState.value = _uiState.value.copy(
      primaryCall = primarySnap,
      waitingCall = waitingSnap,
      totalCallCount = topLevelCalls.size,
      elapsedSeconds = computeElapsedSeconds(primary),
      secondaryElapsedSeconds = computeElapsedSeconds(waiting),
      primaryConnectTimeMillis = resolveActiveConnectTimeMillis(primary),
      waitingElapsedSeconds = computeWaitingElapsedSeconds(waiting),
      isWaitingSilenced = isWaitingSilenced,
      isRecordingPending = pendingRecStillWaiting,
      isCameraEnabled = cameraEnabled,
      isUsingFrontCamera = usingFrontCamera
    )
    if (trackedCalls.isNotEmpty()) {
      ensureTimerRunning()
    }
  }

  /**
   * Places an audio or video call, optionally specifying SIM slot 0 (SIM 1) or 1 (SIM 2).
   * Works cleanly both when Dialer IS default and when it is NOT default.
   */
  fun placeCall(
    context: Context,
    rawNumber: String,
    isVideo: Boolean = false,
    simSlotIndex: Int? = null,
    ignoreDefaultSim: Boolean = AppPreferences.settings.value.ignoreDefaultSim
  ) {
    val number = rawNumber.trim()
    if (number.isEmpty()) {
      AppToast.show(context, "Enter a phone number")
      return
    }
    if (com.example.util.SecretCodeHandler.isAnySpecialCode(number)) {
      com.example.util.SecretCodeHandler.executeMmiOrUssd(context, number, simSlotIndex)
      return
    }
    WaitingCallNotificationManager.markBlockedNotificationsSeenOrReset(context)

    val normNum = CallLogRepository.normalizeNumber(number)
    lastPlacedOutgoingNumber = normNum
    lastPlacedOutgoingMs = System.currentTimeMillis()
    if (isVideo) {
      lastPlacedVideoNormalizedNumber = normNum
      lastPlacedVideoTimestampMs = lastPlacedOutgoingMs
    } else {
      lastPlacedVideoNormalizedNumber = null
    }

    val uri = Uri.fromParts("tel", number, null)
    val resolvedSlot = simSlotIndex ?: if (ignoreDefaultSim) 0 else null
    val accountHandle = resolvedSlot?.let { SimAccountHelper.findPhoneAccountHandle(context, it) }

    if (PermissionHelper.hasCallPhonePermission(context)) {
      try {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val extras = Bundle().apply {
          if (isVideo) {
            putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
          }
          if (accountHandle != null) {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
          }
          if (resolvedSlot != null) {
            putInt("com.android.phone.extra.slot", resolvedSlot)
            putInt("simSlot", resolvedSlot)
          }
        }
        telecom.placeCall(uri, extras)
        return
      } catch (_: Exception) { }

      // Secondary ACTION_CALL attempt (especially reliable when NOT default dialer on OEM ROMs)
      try {
        val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          if (accountHandle != null) {
            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
          }
          if (resolvedSlot != null) {
            putExtra("com.android.phone.extra.slot", resolvedSlot)
            putExtra("simSlot", resolvedSlot)
          }
        }
        context.startActivity(callIntent)
        return
      } catch (_: Exception) { }
    }

    // Fallback when CALL_PHONE isn't granted
    try {
      val intent = Intent(Intent.ACTION_DIAL, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (_: Exception) {
      AppToast.show(context, "Unable to place call")
    }
  }

  fun answerPrimaryCall(
    context: Context,
    asVideo: Boolean? = null,
    openFloating: Boolean? = null
  ) {
    IncomingRingerManager.stopRinging(context)
    val call = primaryCallObj ?: return
    pendingAnsweringKeys.add(call.hashCode())
    val targetVideoState = when {
      asVideo == true -> VideoProfile.STATE_BIDIRECTIONAL
      asVideo == false -> VideoProfile.STATE_AUDIO_ONLY
      else -> call.details?.videoState ?: VideoProfile.STATE_AUDIO_ONLY
    }
    runCatching { call.answer(targetVideoState) }
    WaitingCallNotificationManager.cancelIncomingNotification(context)

    when (openFloating) {
      true -> {
        if (PermissionHelper.hasOverlayPermission(context)) {
          _uiState.value = _uiState.value.copy(isInSmallFloatingMode = true)
          FloatingCallOverlayService.start(context)
        } else {
          launchFullScreenInCallUi(context)
        }
      }
      false -> {
        launchFullScreenInCallUi(context)
      }
      null -> {
        val mode = AppPreferences.settings.value.incomingWindowMode
        if (!_uiState.value.isInSmallFloatingMode && mode != CallWindowPreference.FLOATING_XOS && !isInCallUiVisible) {
          launchFullScreenInCallUi(context)
        }
      }
    }
  }

  fun declineOrEndPrimaryCall() {
    inCallService?.let { IncomingRingerManager.stopRinging(it) }
    val call = primaryCallObj ?: return
    locallyRejectedOrEnded.add(call.hashCode())
    if (mapState(call) == CallLifecycleState.RINGING) {
      runCatching { call.reject(false, null) }
    } else {
      runCatching { call.disconnect() }
    }
  }

  fun silenceWaitingCall(context: Context) {
    val waiting = waitingCallObj ?: return
    silencedWaitingCallKeys.add(waiting.hashCode())
    IncomingRingerManager.stopCallWaitingAlert(context)
    IncomingRingerManager.stopRinging(context)
    _uiState.value = _uiState.value.copy(isWaitingSilenced = true)
  }

  fun rejectWaitingCall(context: Context) {
    IncomingRingerManager.stopRinging(context)
    IncomingRingerManager.stopCallWaitingAlert(context)
    val waiting = waitingCallObj ?: return
    locallyRejectedOrEnded.add(waiting.hashCode())
    lastPostedWaitingNotifKey = null
    if (mapState(waiting) == CallLifecycleState.RINGING) {
      runCatching { waiting.reject(false, null) }
    } else {
      runCatching { waiting.disconnect() }
    }
    WaitingCallNotificationManager.cancelWaitingNotification(context)
  }

  fun holdActiveAndAnswerWaiting(context: Context) {
    IncomingRingerManager.stopRinging(context)
    IncomingRingerManager.stopCallWaitingAlert(context)
    val active = primaryCallObj
    val waiting = waitingCallObj ?: return
    lastPostedWaitingNotifKey = null
    silencedWaitingCallKeys.remove(waiting.hashCode())
    if (active != null && mapState(active) == CallLifecycleState.ACTIVE) {
      runCatching { active.hold() }
    }
    val videoState = waiting.details?.videoState ?: VideoProfile.STATE_AUDIO_ONLY
    runCatching { waiting.answer(videoState) }
    WaitingCallNotificationManager.cancelWaitingNotification(context)
    val mode = AppPreferences.settings.value.incomingWindowMode
    if (!_uiState.value.isInSmallFloatingMode && mode != CallWindowPreference.FLOATING_XOS && !isInCallUiVisible) {
      launchFullScreenInCallUi(context)
    }
  }

  fun endActiveAndAnswerWaiting(context: Context) {
    IncomingRingerManager.stopRinging(context)
    IncomingRingerManager.stopCallWaitingAlert(context)
    val active = primaryCallObj
    val waiting = waitingCallObj ?: return
    lastPostedWaitingNotifKey = null
    silencedWaitingCallKeys.remove(waiting.hashCode())
    if (active != null) {
      locallyRejectedOrEnded.add(active.hashCode())
      runCatching { active.disconnect() }
    }
    val videoState = waiting.details?.videoState ?: VideoProfile.STATE_AUDIO_ONLY
    runCatching { waiting.answer(videoState) }
    WaitingCallNotificationManager.cancelWaitingNotification(context)
    val mode = AppPreferences.settings.value.incomingWindowMode
    if (!_uiState.value.isInSmallFloatingMode && mode != CallWindowPreference.FLOATING_XOS && !isInCallUiVisible) {
      launchFullScreenInCallUi(context)
    }
  }

  fun toggleMute() {
    val nextMute = !_uiState.value.isMuted
    inCallService?.setMuted(nextMute)
  }

  fun toggleSpeaker() {
    val nextRoute = if (_uiState.value.isSpeakerOn) {
      CallAudioState.ROUTE_EARPIECE
    } else {
      CallAudioState.ROUTE_SPEAKER
    }
    inCallService?.setAudioRoute(nextRoute)
  }

  fun setAudioRoute(route: CallAudioRoute) {
    val telecomRoute = when (route) {
      CallAudioRoute.SPEAKER -> CallAudioState.ROUTE_SPEAKER
      CallAudioRoute.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
      CallAudioRoute.WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
      CallAudioRoute.EARPIECE -> CallAudioState.ROUTE_EARPIECE
    }
    inCallService?.setAudioRoute(telecomRoute)
  }

  fun toggleHold() {
    val call = primaryCallObj ?: return
    if (mapState(call) == CallLifecycleState.HOLDING) {
      runCatching { call.unhold() }
    } else {
      runCatching { call.hold() }
    }
  }

  fun swapCalls() {
    val live = trackedCalls.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    val liveKeys = live.map { it.hashCode() }.toSet()
    val topLevel = live.filter { it.parent == null || !liveKeys.contains(it.parent.hashCode()) }.ifEmpty { live }
    val held = topLevel.firstOrNull { mapState(it) == CallLifecycleState.HOLDING }
    val active = topLevel.firstOrNull { mapState(it) == CallLifecycleState.ACTIVE }
    if (held != null) {
      runCatching { held.unhold() }
    } else if (active != null) {
      runCatching { active.hold() }
    }
  }

  fun endSecondaryCall() {
    val sec = waitingCallObj ?: return
    locallyRejectedOrEnded.add(sec.hashCode())
    if (mapState(sec) == CallLifecycleState.RINGING) {
      runCatching { sec.reject(false, null) }
    } else {
      runCatching { sec.disconnect() }
    }
  }

  fun mergeConferenceCalls() {
    val call = primaryCallObj ?: return
    val capabilities = call.details?.callCapabilities ?: 0
    if ((capabilities and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0) {
      runCatching { call.mergeConference() }
      return
    }
    val live = trackedCalls.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    val liveKeys = live.map { it.hashCode() }.toSet()
    val conferenceable = call.conferenceableCalls.firstOrNull()
      ?: live.firstOrNull { it != call && (it.parent == null || !liveKeys.contains(it.parent.hashCode())) }
    if (conferenceable != null) {
      runCatching { call.conference(conferenceable) }
    }
  }

  fun separateConferenceParticipant(callId: String) {
    val child = findCallById(callId) ?: return
    // Guarantee child and all conference siblings are tracked before Telecom splits or dissolves a 2-party conference
    ensureCallTracked(child)
    for (c in trackedCalls.toList()) {
      c.children.forEach { sibling ->
        if (sibling != c) ensureCallTracked(sibling)
      }
    }
    locallyRejectedOrEnded.remove(child.hashCode())
    pendingSplitCallKey = child.hashCode()
    pendingSplitUntilMs = System.currentTimeMillis() + 5_000L

    val res = runCatching { child.splitFromConference() }
    if (res.isFailure) {
      inCallService?.let { AppToast.show(it, "Unable to split participant on this network") }
    }
    inCallService?.let { syncState(it) }
  }

  fun disconnectConferenceParticipant(callId: String) {
    val child = findCallById(callId) ?: return
    locallyRejectedOrEnded.add(child.hashCode())
    runCatching { child.disconnect() }
  }

  private fun findCallById(callId: String): Call? {
    trackedCalls.firstOrNull { it.hashCode().toString() == callId }?.let { return it }
    for (call in trackedCalls) {
      call.children.firstOrNull { it.hashCode().toString() == callId }?.let { return it }
    }
    return null
  }

  fun setInCallUiVisible(visible: Boolean) {
    isInCallUiVisible = visible
    inCallService?.let { syncState(it) }
  }

  private fun findDtmfTargetCall(): Call? {
    val live = trackedCalls.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    return primaryCallObj?.takeIf { mapState(it) == CallLifecycleState.ACTIVE }
      ?: live.firstOrNull { mapState(it) == CallLifecycleState.ACTIVE }
      ?: primaryCallObj
      ?: live.firstOrNull()
  }

  fun sendDtmf(digit: Char) {
    val cleanDigit = digit.uppercaseChar()
    if (cleanDigit !in '0'..'9' && cleanDigit != '*' && cleanDigit != '#' && cleanDigit !in 'A'..'D') {
      return
    }
    DialpadToneHelper.playTone(cleanDigit)

    val call = findDtmfTargetCall() ?: return
    val key = call.hashCode()
    val updatedDigits = ((callDtmfHistory[key].orEmpty()) + cleanDigit).takeLast(64)
    callDtmfHistory[key] = updatedDigits

    val curPrimary = _uiState.value.primaryCall
    if (curPrimary != null && curPrimary.callId == key.toString()) {
      _uiState.value = _uiState.value.copy(
        primaryCall = curPrimary.copy(dtmfDigits = updatedDigits)
      )
    }

    val hadPendingTone = pendingDtmfStopRunnable != null || pendingDtmfStartRunnable != null
    pendingDtmfStartRunnable?.let { handler.removeCallbacks(it) }
    pendingDtmfStopRunnable?.let { handler.removeCallbacks(it) }
    pendingDtmfStartRunnable = null
    pendingDtmfStopRunnable = null

    val triggerTone: () -> Unit = {
      val liveCall = if (mapState(call) != CallLifecycleState.DISCONNECTED) call else findDtmfTargetCall()
      if (liveCall != null) {
        activeDtmfCall = liveCall
        val played = runCatching { liveCall.playDtmfTone(cleanDigit) }.isSuccess
        if (!played && liveCall.children.isNotEmpty()) {
          liveCall.children.firstOrNull()?.let { child ->
            activeDtmfCall = child
            runCatching { child.playDtmfTone(cleanDigit) }
          }
        }
        val stopTask = Runnable {
          runCatching { activeDtmfCall?.stopDtmfTone() }
          pendingDtmfStopRunnable = null
        }
        pendingDtmfStopRunnable = stopTask
        handler.postDelayed(stopTask, 280L)
      }
    }

    if (hadPendingTone) {
      // Explicitly close previous modem/IMS tone and give 45ms inter-digit gap so IVR detects rapid taps
      runCatching { (activeDtmfCall ?: call).stopDtmfTone() }
      val startTask = Runnable {
        pendingDtmfStartRunnable = null
        triggerTone()
      }
      pendingDtmfStartRunnable = startTask
      handler.postDelayed(startTask, 45L)
    } else {
      triggerTone()
    }
  }

  fun toggleUpgradeToVideo(context: Context) {
    val call = primaryCallObj ?: return
    val videoCall = call.videoCall
    if (videoCall == null) {
      markVideoCallFailed(context)
      return
    }
    val currentlyVideo = VideoProfile.isVideo(call.details?.videoState ?: 0)
    val nextState = if (currentlyVideo) VideoProfile.STATE_AUDIO_ONLY else VideoProfile.STATE_BIDIRECTIONAL
    if (!currentlyVideo) {
      requestedVideoKeys.add(call.hashCode())
    }
    runCatching {
      videoCall.sendSessionModifyRequest(VideoProfile(nextState))
    }.onFailure {
      markVideoCallFailed(context)
    }
  }

  fun setVoiceEffect(context: Context, preset: VoiceEffectPreset) {
    val (ok, msg) = VoiceChangerManager.applyEffect(context, preset)
    if (ok) {
      _uiState.value = _uiState.value.copy(activeVoiceEffect = preset)
    }
    AppToast.show(context, msg)
  }

  fun clearVideoFailureMessage() {
    _uiState.value = _uiState.value.copy(videoFailureMessage = null)
  }

  fun attachVideoSurfaces(preview: Surface?, display: Surface?) {
    previewSurfaceRef = preview
    displaySurfaceRef = display
    applyVideoSurfaces()
  }

  fun toggleCamera(context: Context) {
    cameraEnabled = !cameraEnabled
    val videoCall = primaryCallObj?.videoCall
    if (videoCall != null) {
      val camId = if (cameraEnabled) findCameraId(context, usingFrontCamera) else null
      runCatching { videoCall.setCamera(camId) }
    }
    _uiState.value = _uiState.value.copy(isCameraEnabled = cameraEnabled)
  }

  fun switchCamera(context: Context) {
    usingFrontCamera = !usingFrontCamera
    cameraEnabled = true
    val videoCall = primaryCallObj?.videoCall
    if (videoCall != null) {
      val camId = findCameraId(context, usingFrontCamera)
      runCatching { videoCall.setCamera(camId) }
    }
    _uiState.value = _uiState.value.copy(isUsingFrontCamera = usingFrontCamera, isCameraEnabled = true)
  }

  private fun applyVideoSurfaces() {
    val videoCall = primaryCallObj?.videoCall ?: return
    runCatching {
      videoCall.setPreviewSurface(previewSurfaceRef)
      videoCall.setDisplaySurface(displaySurfaceRef)
      val ctx = inCallService
      if (ctx != null && cameraEnabled) {
        videoCall.setCamera(findCameraId(ctx, usingFrontCamera))
      }
    }
  }

  private fun findCameraId(context: Context, front: Boolean): String? {
    return try {
      val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val targetFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
      cm.cameraIdList.firstOrNull { id ->
        cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == targetFacing
      } ?: cm.cameraIdList.firstOrNull()
    } catch (_: Exception) {
      null
    }
  }

  fun toggleRecording(context: Context) {
    if (_uiState.value.isRecording) {
      val (_, elapsedRecSec) = CallRecorderManager.stopRecording(context)
      recordingStartMs = 0L
      _uiState.value = _uiState.value.copy(
        isRecording = false,
        isRecordingPending = false,
        recordingSeconds = 0L,
        recordingEngineUsed = ""
      )
      AppToast.show(context, "Recording saved ${formatDuration(elapsedRecSec)}sec")
      return
    }

    if (_uiState.value.isRecordingPending) {
      _uiState.value = _uiState.value.copy(isRecordingPending = false)
      AppToast.show(context, "Recording cancelled")
      return
    }

    val activeCall = primaryCallObj?.takeIf { mapState(it) == CallLifecycleState.ACTIVE }
      ?: trackedCalls.firstOrNull { mapState(it) == CallLifecycleState.ACTIVE }

    if (activeCall != null) {
      val snap = toSnapshot(context, activeCall)
      startRecordingInternal(context, snap.number, snap.displayName)
    } else {
      // Call is still ringing/dialing/connecting -> arm recording and wait until ACTIVE
      _uiState.value = _uiState.value.copy(isRecordingPending = true)
    }
  }

  private fun startRecordingInternal(context: Context, number: String, callerName: String) {
    val settings = AppPreferences.settings.value
    if (!com.example.util.PrivilegeHelper.hasCaptureAudioOutputPermission(context) &&
      (settings.enableRootEnhancements || settings.enableShizukuEnhancements) &&
      (settings.recordingEngine == com.example.model.RecordingEngine.AUTO ||
        settings.recordingEngine == com.example.model.RecordingEngine.ROOT ||
        settings.recordingEngine == com.example.model.RecordingEngine.SHIZUKU)
    ) {
      Thread {
        runCatching {
          com.example.util.PrivilegeHelper.grantTwoWayRecordingPrivilege(context.applicationContext)
        }
      }.start()
    }

    if (!FloatingCallOverlayService.isForegroundMicActive && PermissionHelper.hasRecordAudioPermission(context)) {
      _uiState.value = _uiState.value.copy(isRecordingPending = true)
      FloatingCallOverlayService.startForRecording(context, number, callerName)
      return
    }

    performActualStartRecording(context, number, callerName)
  }

  fun onForegroundMicServiceReadyAndStartRecording(
    context: Context,
    fallbackNumber: String,
    fallbackCallerName: String
  ) {
    if (_uiState.value.isRecording || trackedCalls.isEmpty()) return
    val activeCall = primaryCallObj?.takeIf { mapState(it) == CallLifecycleState.ACTIVE }
      ?: trackedCalls.firstOrNull { mapState(it) == CallLifecycleState.ACTIVE }
    if (activeCall != null) {
      val snap = toSnapshot(context, activeCall)
      performActualStartRecording(context, snap.number, snap.displayName)
    } else if (_uiState.value.isRecordingPending) {
      performActualStartRecording(context, fallbackNumber, fallbackCallerName)
    }
  }

  private fun performActualStartRecording(context: Context, number: String, callerName: String) {
    val (ok, engine) = CallRecorderManager.startRecording(
      context = context,
      phoneNumber = number,
      callerName = callerName,
      isCallMutedProvider = { _uiState.value.isMuted },
      onEngineSwitched = { newEngine ->
        handler.post {
          if (_uiState.value.isRecording) {
            _uiState.value = _uiState.value.copy(recordingEngineUsed = newEngine)
          }
        }
      }
    )
    if (ok) {
      recordingStartMs = System.currentTimeMillis()
      _uiState.value = _uiState.value.copy(
        isRecording = true,
        isRecordingPending = false,
        recordingSeconds = 0L,
        recordingEngineUsed = engine
      )
    } else {
      _uiState.value = _uiState.value.copy(isRecordingPending = false)
      AppToast.show(context, "Recording failed: $engine")
    }
  }

  fun launchFullScreenInCallUi(context: Context) {
    _uiState.value = _uiState.value.copy(isInSmallFloatingMode = false)
    val intent = Intent(context, InCallActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    context.startActivity(intent)
  }

  fun minimizeToSmallFloating(context: Context) {
    if (PermissionHelper.hasOverlayPermission(context)) {
      _uiState.value = _uiState.value.copy(isInSmallFloatingMode = true)
      FloatingCallOverlayService.start(context)
    }
  }

  private fun toSnapshot(context: Context, call: Call): ActiveCallSnapshot {
    val details = call.details
    val props = details?.callProperties ?: 0
    val liveChildren = call.children.filter {
      val st = mapState(it)
      st != CallLifecycleState.DISCONNECTED && st != CallLifecycleState.DISCONNECTING
    }
    val isConference = (props and Call.Details.PROPERTY_CONFERENCE) != 0 || liveChildren.isNotEmpty()
    val participants = if (isConference) {
      liveChildren.map { child ->
        val cNum = child.details?.handle?.schemeSpecificPart.orEmpty().ifBlank { "Participant" }
        val (cName, cPhoto) = lookupContactNameAndPhoto(context, cNum)
        val cDisplay = child.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: cName ?: cNum
        val canSep = ((child.details?.callCapabilities ?: 0) and Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE) != 0
        com.example.model.ConferenceParticipant(
          callId = child.hashCode().toString(),
          number = cNum,
          displayName = cDisplay,
          photoUri = cPhoto,
          canSeparate = canSep
        )
      }
    } else emptyList()

    val number = if (isConference) {
      "${participants.size.coerceAtLeast(2)} participants"
    } else {
      details?.handle?.schemeSpecificPart.orEmpty().ifBlank { "Private Number" }
    }
    val (contactName, photoUri) = if (isConference) (null to null) else lookupContactNameAndPhoto(context, number)
    val callerDisplay = if (isConference) {
      "Conference Call"
    } else {
      details?.callerDisplayName?.takeIf { it.isNotBlank() }
        ?: contactName
        ?: number
    }

    val isVideo = VideoProfile.isVideo(details?.videoState ?: 0)
    val capabilities = details?.callCapabilities ?: 0
    val isWifiProp = (props and Call.Details.PROPERTY_WIFI) != 0
    val isHdProp = (props and Call.Details.PROPERTY_HIGH_DEF_AUDIO) != 0

    val badge = SimAccountHelper.detectCurrentNetworkBadge(
      context = context,
      isWifiCallProperty = isWifiProp,
      isHdProperty = isHdProp
    )
    bestNetworkBadge[call.hashCode()] = badge

    val simDetails = SimAccountHelper.resolveSimDetails(
      context,
      details?.accountHandle?.id
    )

    val canHold = (capabilities and Call.Details.CAPABILITY_HOLD) != 0 ||
      (capabilities and Call.Details.CAPABILITY_SUPPORT_HOLD) != 0

    val callState = mapState(call)
    val key = call.hashCode()
    val (isIncomingCall, isMixedConf) = if (isConference) {
      val (hasIn, hasOut, isMixed) = resolveConferenceDirectionFlags(call)
      val incomingFlag = if (isMixed) true else (hasIn && !hasOut)
      incomingFlag to isMixed
    } else {
      val isOutgoing = wasOutgoingMap[key] ?: isCallOutgoing(call, number)
      (!isOutgoing) to false
    }

    val resolvedLastCall = if (isConference || number.isBlank() || number == "Private Number") {
      null
    } else {
      lastCallInfoCache.getOrPut(key) {
        CallLogRepository.findLastCallInfo(context, number)
      }
    }

    val isBlocked = !isConference && number.isNotBlank() && number != "Private Number" &&
      BlocklistRepository.isBlocked(context, number)

    return ActiveCallSnapshot(
      callId = key.toString(),
      number = number,
      displayName = callerDisplay,
      photoUri = photoUri,
      state = callState,
      isIncoming = isIncomingCall,
      isVideoCall = isVideo,
      connectTimeMillis = resolveActiveConnectTimeMillis(call).takeIf { it > 0L } ?: (details?.connectTimeMillis ?: 0L),
      simSlotLabel = simDetails.slotLabel,
      simCarrierName = simDetails.carrierName,
      simSlotNumber = simDetails.slotNumber,
      simColorArgb = simDetails.iconTintColor,
      networkBadge = badge,
      disconnectReason = if (isIncomingCall) null else resolveDisconnectReason(details?.disconnectCause),
      canHold = canHold,
      canUpgradeToVideo = (capabilities and Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_TX) != 0 || isVideo,
      isConference = isConference,
      isConferenceMixedDirections = isMixedConf,
      conferenceParticipants = participants,
      lastCallTimestampMillis = resolvedLastCall?.timestampMillis,
      lastCallInfo = resolvedLastCall,
      isNumberBlocked = isBlocked,
      dtmfDigits = callDtmfHistory[key].orEmpty()
    )
  }

  /**
   * Resolves genuine carrier/operator error disconnect causes; returns null for normal
   * hangup ("Local" / "Normal" / "Remote" / "Canceled") so "local" never surfaces as an error.
   */
  private fun resolveDisconnectReason(cause: DisconnectCause?): String? {
    if (cause == null) return null
    val label = cause.label?.toString()?.trim()?.takeIf { it.isNotBlank() }
    val reason = cause.reason?.trim()?.takeIf { it.isNotBlank() }
    val desc = cause.description?.toString()?.trim()?.takeIf { it.isNotBlank() }

    fun isRoutineLabel(text: String): Boolean {
      return text.equals("local", true) ||
        text.equals("normal", true) ||
        text.equals("unknown", true) ||
        text.equals("remote", true) ||
        text.equals("canceled", true) ||
        text.equals("cancelled", true) ||
        text.equals("normal clearing", true) ||
        text.equals("incoming rejected", true) ||
        text.contains("incoming rejected", true)
    }

    if (cause.code == DisconnectCause.LOCAL || cause.code == DisconnectCause.REMOTE || cause.code == DisconnectCause.CANCELED) {
      return null
    }

    if (label != null && !isRoutineLabel(label)) return label
    if (desc != null && !isRoutineLabel(desc)) return desc
    if (reason != null && !isRoutineLabel(reason)) {
      return when {
        reason.contains("UNASSIGNED", true) || reason.contains("INVALID", true) -> "Invalid number"
        reason.contains("BUSY", true) -> "User busy"
        reason.contains("SERVER", true) || reason.contains("TEMP", true) -> "Server error"
        else -> reason.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
      }
    }

    return when (cause.code) {
      DisconnectCause.BUSY -> "User busy"
      DisconnectCause.ERROR -> "Server error"
      DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED,
      DisconnectCause.RESTRICTED -> "Can't connect"
      DisconnectCause.REJECTED -> "Call declined"
      DisconnectCause.MISSED -> "No answer"
      else -> null
    }
  }

  private fun lookupContactNameAndPhoto(context: Context, number: String): Pair<String?, String?> {
    if (number.isBlank() || !PermissionHelper.hasPermission(context, Manifest.permission.READ_CONTACTS)) {
      return null to null
    }
    return try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
      context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI),
        null,
        null,
        null
      )?.use { c ->
        if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null
      } ?: (null to null)
    } catch (_: Exception) {
      null to null
    }
  }

  private fun mapState(call: Call?): CallLifecycleState {
    if (call == null) return CallLifecycleState.IDLE
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      call.details?.state ?: @Suppress("DEPRECATION") call.state
    } else {
      @Suppress("DEPRECATION") call.state
    }
    return when (code) {
      Call.STATE_RINGING -> CallLifecycleState.RINGING
      Call.STATE_DIALING -> CallLifecycleState.DIALING
      Call.STATE_CONNECTING -> CallLifecycleState.CONNECTING
      Call.STATE_ACTIVE -> CallLifecycleState.ACTIVE
      Call.STATE_HOLDING -> CallLifecycleState.HOLDING
      Call.STATE_DISCONNECTING -> CallLifecycleState.DISCONNECTING
      Call.STATE_DISCONNECTED -> CallLifecycleState.DISCONNECTED
      else -> CallLifecycleState.IDLE
    }
  }

  private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
  }
}

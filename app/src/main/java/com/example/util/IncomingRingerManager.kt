package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.example.data.AppPreferences
import com.example.model.CallWaitingAlertMode
import com.example.model.IncomingVibrationPattern

/**
 * Manages looping ringtone playback & call vibration whenever Dialer handles incoming ringing calls,
 * plus customizable Call Waiting "tut-tut" sound and 350ms x2 vibration patterns while another call is active.
 */
object IncomingRingerManager {

  const val CALL_WAITING_VIBRATE_MS = 350L
  private const val CALL_WAITING_VIBRATE_GAP_MS = 220L
  private const val CALL_WAITING_SOUND_INTERVAL_MS = 3000L
  private const val CALL_WAITING_VIBRATE_REPEAT_MS = 20_000L

  @Volatile
  private var isRinging = false
  private var mediaPlayer: MediaPlayer? = null
  private var fallbackRingtone: Ringtone? = null

  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile
  private var isWaitingAlertActive = false
  @Volatile
  private var activeWaitingCallKey: String? = null
  private var waitingToneGen: ToneGenerator? = null
  private var waitingSoundRunnable: Runnable? = null
  private var waitingVibrateRunnable: Runnable? = null

  @Synchronized
  fun startRinging(context: Context, simSlotIndex: Int? = null) {
    stopCallWaitingAlert(context)
    if (isRinging) return
    val appCtx = context.applicationContext
    val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

    if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
      return
    }

    isRinging = true

    AppPreferences.init(appCtx)
    val patternPref = AppPreferences.settings.value.incomingVibrationPattern

    if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
      startLoopingRingtone(appCtx, simSlotIndex)
    }
    if (shouldVibrateOnNormalIncoming(appCtx, ringerMode, patternPref)) {
      startLoopingVibration(appCtx, patternPref)
    }
  }

  @Synchronized
  fun stopRinging(context: Context) {
    if (!isRinging) return
    isRinging = false

    runCatching {
      mediaPlayer?.let { mp ->
        if (mp.isPlaying) mp.stop()
        mp.reset()
        mp.release()
      }
    }
    mediaPlayer = null

    runCatching {
      fallbackRingtone?.stop()
    }
    fallbackRingtone = null

    runCatching {
      getVibrator(context.applicationContext)?.cancel()
    }
  }

  /**
   * Starts the Call Waiting tut-tut tone and 350ms x2 vibration pattern while an ongoing call is active.
   * Never plays a full ringtone during an active call.
   */
  @Synchronized
  fun startCallWaitingAlert(context: Context, waitingCallKey: String, mode: CallWaitingAlertMode) {
    val appCtx = context.applicationContext
    stopRinging(appCtx)

    if (isWaitingAlertActive && activeWaitingCallKey == waitingCallKey) {
      return
    }
    stopCallWaitingAlert(appCtx)

    val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    val ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
      return
    }

    isWaitingAlertActive = true
    activeWaitingCallKey = waitingCallKey

    val allowSound = ringerMode == AudioManager.RINGER_MODE_NORMAL
    val allowVibrate = ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
      isSystemCallVibrationEnabled(appCtx, ringerMode)

    when (mode) {
      CallWaitingAlertMode.SYSTEM_DEFAULT -> {
        if (allowSound) {
          playCallWaitingTutTut()
        }
        if (allowVibrate) {
          vibrateTwice350ms(appCtx)
        }
      }

      CallWaitingAlertMode.SOUND_TWICE_VIBRATE_TWICE -> {
        if (allowVibrate) {
          vibrateTwice350ms(appCtx)
        }
        if (allowSound) {
          playCallWaitingTutTut()
          val secondSound = Runnable {
            if (isWaitingAlertActive && activeWaitingCallKey == waitingCallKey) {
              playCallWaitingTutTut()
            }
          }
          waitingSoundRunnable = secondSound
          mainHandler.postDelayed(secondSound, CALL_WAITING_SOUND_INTERVAL_MS)
        }
      }

      CallWaitingAlertMode.SOUND_CONTINUOUS_VIBRATE_TWICE_ONCE -> {
        if (allowVibrate) {
          vibrateTwice350ms(appCtx)
        }
        if (allowSound) {
          scheduleContinuousTutTut(waitingCallKey)
        }
      }

      CallWaitingAlertMode.SOUND_CONTINUOUS_VIBRATE_EVERY_20S -> {
        if (allowSound) {
          scheduleContinuousTutTut(waitingCallKey)
        }
        if (allowVibrate) {
          val vibrateLoop = object : Runnable {
            override fun run() {
              if (!isWaitingAlertActive || activeWaitingCallKey != waitingCallKey) return
              vibrateTwice350ms(appCtx)
              mainHandler.postDelayed(this, CALL_WAITING_VIBRATE_REPEAT_MS)
            }
          }
          waitingVibrateRunnable = vibrateLoop
          mainHandler.post(vibrateLoop)
        }
      }
    }
  }

  @Synchronized
  fun stopCallWaitingAlert(context: Context? = null) {
    if (!isWaitingAlertActive && waitingSoundRunnable == null && waitingVibrateRunnable == null) {
      activeWaitingCallKey = null
      return
    }
    isWaitingAlertActive = false
    activeWaitingCallKey = null
    waitingSoundRunnable?.let { mainHandler.removeCallbacks(it) }
    waitingVibrateRunnable?.let { mainHandler.removeCallbacks(it) }
    waitingSoundRunnable = null
    waitingVibrateRunnable = null

    runCatching {
      waitingToneGen?.stopTone()
      waitingToneGen?.release()
    }
    waitingToneGen = null

    if (context != null) {
      runCatching {
        if (!isRinging) {
          getVibrator(context.applicationContext)?.cancel()
        }
      }
    }
  }

  private fun scheduleContinuousTutTut(expectedKey: String) {
    val soundLoop = object : Runnable {
      override fun run() {
        if (!isWaitingAlertActive || activeWaitingCallKey != expectedKey) return
        playCallWaitingTutTut()
        mainHandler.postDelayed(this, CALL_WAITING_SOUND_INTERVAL_MS)
      }
    }
    waitingSoundRunnable = soundLoop
    mainHandler.post(soundLoop)
  }

  private fun playCallWaitingTutTut() {
    runCatching {
      val gen = waitingToneGen ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 82).also {
        waitingToneGen = it
      }
      gen.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, 480)
    }.onFailure {
      runCatching {
        val fallback = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
        fallback.startTone(ToneGenerator.TONE_PROP_BEEP2, 420)
        mainHandler.postDelayed({ runCatching { fallback.release() } }, 500L)
      }
    }
  }

  @Suppress("MissingPermission")
  private fun vibrateTwice350ms(context: Context) {
    runCatching {
      val vibrator = getVibrator(context) ?: return
      if (!vibrator.hasVibrator()) return
      // Two crisp 350ms vibrations separated by 220ms pause, played once (-1)
      val pattern = longArrayOf(0L, CALL_WAITING_VIBRATE_MS, CALL_WAITING_VIBRATE_GAP_MS, CALL_WAITING_VIBRATE_MS)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(pattern, -1)
      }
    }
  }

  fun isSystemCallVibrationEnabled(context: Context, ringerMode: Int): Boolean {
    if (ringerMode == AudioManager.RINGER_MODE_SILENT) return false
    if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) return true
    if (ringerMode != AudioManager.RINGER_MODE_NORMAL) return false

    val ringIntensity = runCatching {
      Settings.System.getInt(context.contentResolver, "ring_vibration_intensity", -1)
    }.getOrDefault(-1)
    if (ringIntensity == 0) return false
    if (ringIntensity > 0) return true

    return runCatching {
      @Suppress("DEPRECATION")
      Settings.System.getInt(context.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 1) == 1
    }.getOrDefault(true)
  }

  private fun shouldVibrateOnNormalIncoming(
    context: Context,
    ringerMode: Int,
    pattern: IncomingVibrationPattern
  ): Boolean {
    if (pattern == IncomingVibrationPattern.OFF) return false
    return isSystemCallVibrationEnabled(context, ringerMode)
  }

  fun waveformForPattern(pattern: IncomingVibrationPattern): LongArray {
    return when (pattern) {
      IncomingVibrationPattern.SYSTEM_DEFAULT -> longArrayOf(0L, 1000L, 1000L)
      IncomingVibrationPattern.DOUBLE_BEAT -> longArrayOf(0L, 280L, 180L, 280L, 900L)
      IncomingVibrationPattern.HEARTBEAT -> longArrayOf(0L, 160L, 130L, 380L, 850L)
      IncomingVibrationPattern.RAPID_PULSE -> longArrayOf(0L, 200L, 150L, 200L, 150L, 200L, 700L)
      IncomingVibrationPattern.LONG_WAVE -> longArrayOf(0L, 1200L, 800L)
      IncomingVibrationPattern.OFF -> longArrayOf()
    }
  }

  @Suppress("MissingPermission")
  fun previewVibrationPattern(context: Context, pattern: IncomingVibrationPattern) {
    val vibrator = getVibrator(context.applicationContext) ?: return
    runCatching { vibrator.cancel() }
    val wave = waveformForPattern(pattern)
    if (wave.isEmpty()) return
    // Trim trailing silence pause for clean short 1-shot preview
    val previewWave = if (wave.size > 2) wave.copyOf(wave.size - 1) else wave
    vibrateWithRingAttributes(vibrator, previewWave, repeatIndex = -1)
  }

  @Suppress("MissingPermission")
  fun previewCallWaitingAlert(context: Context, mode: CallWaitingAlertMode) {
    val appCtx = context.applicationContext
    vibrateTwice350ms(appCtx)
    runCatching {
      val gen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
      gen.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, 450)
      mainHandler.postDelayed({ runCatching { gen.release() } }, 550L)
    }
  }

  private fun startLoopingRingtone(context: Context, simSlotIndex: Int?) {
    val ringUri = if (simSlotIndex == 1) {
      val uriStr = runCatching {
        Settings.System.getString(context.contentResolver, "ringtone_2")
          ?: Settings.System.getString(context.contentResolver, "ringtone_sim2")
          ?: Settings.System.getString(context.contentResolver, "sim2_ringtone")
      }.getOrNull()
      uriStr?.let { android.net.Uri.parse(it) }
        ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    } else {
      val uriStr = runCatching {
        Settings.System.getString(context.contentResolver, Settings.System.RINGTONE)
          ?: Settings.System.getString(context.contentResolver, "ringtone")
      }.getOrNull()
      uriStr?.let { android.net.Uri.parse(it) }
        ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }
    if (ringUri == null) return

    val startedMp = runCatching {
      val mp = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        setDataSource(context, ringUri)
        isLooping = true
        prepare()
        start()
      }
      mediaPlayer = mp
      true
    }.getOrDefault(false)

    if (!startedMp) {
      runCatching {
        val rt = RingtoneManager.getRingtone(context, ringUri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          rt?.isLooping = true
        }
        rt?.play()
        fallbackRingtone = rt
      }
    }
  }

  @Suppress("MissingPermission")
  private fun startLoopingVibration(context: Context, patternPref: IncomingVibrationPattern) {
    val vibrator = getVibrator(context) ?: return
    val pattern = waveformForPattern(patternPref)
    if (pattern.isEmpty()) return
    vibrateWithRingAttributes(vibrator, pattern, repeatIndex = 0)
  }

  @Suppress("MissingPermission")
  private fun vibrateWithRingAttributes(vibrator: Vibrator, pattern: LongArray, repeatIndex: Int) {
    runCatching {
      if (!vibrator.hasVibrator()) return
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val effect = VibrationEffect.createWaveform(pattern, repeatIndex)
        val attrs = VibrationAttributes.Builder()
          .setUsage(VibrationAttributes.USAGE_RINGTONE)
          .build()
        vibrator.vibrate(effect, attrs)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = VibrationEffect.createWaveform(pattern, repeatIndex)
        @Suppress("DEPRECATION")
        val attrs = AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build()
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, attrs)
      } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(pattern, repeatIndex)
      }
    }
  }

  private fun getVibrator(context: Context): Vibrator? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
      vm?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
  }
}

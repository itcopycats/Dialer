package com.example.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.model.GameTarget
import com.example.model.SessionConfig
import com.example.model.SessionState
import com.example.model.SessionStatus
import com.example.util.UsageAccessHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SessionManager {
  private val _state = MutableStateFlow(SessionState())
  val state: StateFlow<SessionState> = _state.asStateFlow()

  private const val PREFS_NAME = "firelimit_permanent_prefs"
  private const val KEY_LIMIT = "key_limit_sec"
  private const val KEY_WARNING = "key_warning_sec"
  private const val KEY_VIBRATE = "key_vibrate"
  private const val KEY_SOUND = "key_sound"
  private const val KEY_INCLUDE_SETTINGS = "key_include_settings"
  private const val KEY_THEME_MODE = "key_theme_mode"
  private const val KEY_AMOLED_BLACK = "key_amoled_black"
  private const val PREF_LAST_SESSION_PREFIX = "last_session_"

  private var activeGamePackage: String? = null

  fun init(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val limit = prefs.getLong(KEY_LIMIT, 7200L) // 2 hours default
    val warning = prefs.getLong(KEY_WARNING, 6000L) // 1 hour 40 min default
    val vibrate = prefs.getBoolean(KEY_VIBRATE, true)
    val sound = prefs.getBoolean(KEY_SOUND, true)
    val includeSettings = prefs.getBoolean(KEY_INCLUDE_SETTINGS, true)
    val themeMode = prefs.getString(KEY_THEME_MODE, "AUTO") ?: "AUTO"
    val amoledBlack = prefs.getBoolean(KEY_AMOLED_BLACK, true)

    val config = SessionConfig(
      limitSeconds = limit,
      warningSeconds = warning,
      vibrationEnabled = vibrate,
      soundEnabled = sound,
      includeSettingsForTesting = includeSettings,
      themeMode = themeMode,
      useAmoledPureBlack = amoledBlack
    )

    val hasPerm = UsageAccessHelper.hasUsageAccessPermission(context)
    _state.value = _state.value.copy(
      config = config,
      hasUsageAccessPermission = hasPerm
    )

    // Load initial usage data recovered from system UsageStatsManager
    refreshPermanentUsageStats(context)

    // Start background monitor service automatically
    startService(context)
  }

  fun refreshPermission(context: Context) {
    val hasPerm = UsageAccessHelper.hasUsageAccessPermission(context)
    _state.value = _state.value.copy(hasUsageAccessPermission = hasPerm)
    if (hasPerm) {
      refreshPermanentUsageStats(context)
      startService(context)
    }
  }

  fun refreshPermanentUsageStats(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val targets = mutableListOf(
      GameTarget.FREE_FIRE_NORMAL,
      GameTarget.FREE_FIRE_MAX,
      GameTarget.FREE_FIRE_ADVANCE
    )
    if (_state.value.config.includeSettingsForTesting) {
      targets.add(GameTarget.ANDROID_SETTINGS)
    }

    val records = targets.map { target ->
      val lastSec = prefs.getLong(PREF_LAST_SESSION_PREFIX + target.packageName, 0L)
      UsageAccessHelper.fetchUsageStatsForPackage(context, target, lastSec)
    }

    _state.value = _state.value.copy(gameUsageRecords = records)
  }

  fun updateConfig(context: Context, newConfig: SessionConfig) {
    _state.value = _state.value.copy(config = newConfig)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit()
      .putLong(KEY_LIMIT, newConfig.limitSeconds)
      .putLong(KEY_WARNING, newConfig.warningSeconds)
      .putBoolean(KEY_VIBRATE, newConfig.vibrationEnabled)
      .putBoolean(KEY_SOUND, newConfig.soundEnabled)
      .putBoolean(KEY_INCLUDE_SETTINGS, newConfig.includeSettingsForTesting)
      .putString(KEY_THEME_MODE, newConfig.themeMode)
      .putBoolean(KEY_AMOLED_BLACK, newConfig.useAmoledPureBlack)
      .apply()

    refreshPermanentUsageStats(context)

    val intent = Intent(context, SessionTimerService::class.java).apply {
      action = SessionTimerService.ACTION_UPDATE_CONFIG
    }
    context.startService(intent)
  }

  fun startService(context: Context) {
    val intent = Intent(context, SessionTimerService::class.java).apply {
      action = SessionTimerService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
  }

  fun endSessionAndReset(context: Context) {
    val current = _state.value
    if (current.elapsedSeconds > 10 && current.activeGamePackage != null) {
      recordSessionEnded(context, current.activeGamePackage, current.elapsedSeconds)
    }

    UsageAccessHelper.resetForNewSession()
    activeGamePackage = null

    _state.value = current.copy(
      elapsedSeconds = 0L,
      status = SessionStatus.IDLE,
      warningTriggered = false,
      limitTriggered = false,
      detectedForegroundPackage = null,
      isTargetAppActive = false,
      activeGamePackage = null,
      activeGameDisplayName = "Free Fire"
    )

    val intent = Intent(context, SessionTimerService::class.java).apply {
      action = SessionTimerService.ACTION_RESET_SESSION
    }
    ContextCompat.startForegroundService(context, intent)
  }

  fun recordSessionEnded(context: Context, packageName: String?, durationSec: Long) {
    if (packageName != null && durationSec > 10) {
      val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      prefs.edit().putLong(PREF_LAST_SESSION_PREFIX + packageName, durationSec).apply()
      refreshPermanentUsageStats(context)
    }
  }

  internal fun updateFromService(
    elapsed: Long,
    status: SessionStatus,
    warningHit: Boolean,
    limitHit: Boolean,
    foregroundPkg: String?,
    isTargetActive: Boolean,
    activePkg: String?,
    gameDisplayName: String
  ) {
    if (activeGamePackage != activePkg && isTargetActive) {
      activeGamePackage = activePkg
    } else if (!isTargetActive && status == SessionStatus.IDLE) {
      activeGamePackage = null
    }

    _state.value = _state.value.copy(
      elapsedSeconds = elapsed,
      status = status,
      warningTriggered = warningHit,
      limitTriggered = limitHit,
      detectedForegroundPackage = foregroundPkg,
      isTargetAppActive = isTargetActive,
      activeGamePackage = activePkg,
      activeGameDisplayName = gameDisplayName
    )
  }
}

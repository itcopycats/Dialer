package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.GameTarget
import com.example.model.SessionState
import com.example.model.SessionStatus
import com.example.util.UsageAccessHelper

class SessionTimerService : Service() {

  companion object {
    const val CHANNEL_MONITOR = "firelimit_session_monitor"
    const val CHANNEL_ALERTS = "firelimit_session_alerts"

    const val NOTIFICATION_ID_ONGOING = 1001
    const val NOTIFICATION_ID_WARNING = 1002
    const val NOTIFICATION_ID_LIMIT = 1003

    const val ACTION_START = "firelimit.bypass.ACTION_START"
    const val ACTION_STOP = "firelimit.bypass.ACTION_STOP"
    const val ACTION_UPDATE_CONFIG = "firelimit.bypass.ACTION_UPDATE_CONFIG"
    const val ACTION_RESET_SESSION = "firelimit.bypass.ACTION_RESET_SESSION"
  }

  private val handler = Handler(Looper.getMainLooper())
  private var isServiceActive = false
  private var elapsedSeconds = 0L
  private var lastTickRealtimeMs = 0L
  private var warningTriggered = false
  private var limitTriggered = false
  private var currentActivePackage: String? = null
  private var activeGameName = "Free Fire"
  private var lastStatsRefreshElapsed = 0L

  private val monitorRunnable = object : Runnable {
    override fun run() {
      if (isServiceActive) {
        val config = SessionManager.state.value.config
        val hasUsageAccess = UsageAccessHelper.hasUsageAccessPermission(this@SessionTimerService)
        var topForegroundPkg: String? = null
        var activeGamePkg: String? = null
        var isGameInForeground = false
        var stoppedBySwipe = false

        if (hasUsageAccess) {
          val snapshot = UsageAccessHelper.pollTrackingSnapshot(
            context = this@SessionTimerService,
            includeSettings = config.includeSettingsForTesting
          )
          topForegroundPkg = snapshot.topForegroundPackage
          activeGamePkg = snapshot.activeSessionPackage
          isGameInForeground = snapshot.isGameInForeground
          stoppedBySwipe = snapshot.sessionStoppedByRecentsSwipe
        } else {
          UsageAccessHelper.resetForegroundTracking()
        }

        val isScreenActive = UsageAccessHelper.isScreenInteractiveAndUnlocked(this@SessionTimerService)
        val isTargetActive = activeGamePkg != null && UsageAccessHelper.isMonitoredPackage(activeGamePkg, config.includeSettingsForTesting)
        var currentStatus = SessionManager.state.value.status

        if (isTargetActive && activeGamePkg != null) {
          if (currentActivePackage != activeGamePkg) {
            // If switching straight from one game to another, record the previous game session
            if (currentActivePackage != null && elapsedSeconds > 10) {
              SessionManager.recordSessionEnded(this@SessionTimerService, currentActivePackage, elapsedSeconds)
              elapsedSeconds = 0L
              warningTriggered = false
              limitTriggered = false
              lastStatsRefreshElapsed = 0L
              lastTickRealtimeMs = 0L
            }
            currentActivePackage = activeGamePkg
            activeGameName = when (activeGamePkg) {
              GameTarget.FREE_FIRE_NORMAL.packageName -> "Free Fire"
              GameTarget.FREE_FIRE_MAX.packageName -> "Free Fire MAX"
              GameTarget.FREE_FIRE_ADVANCE.packageName -> "Free Fire Advance"
              GameTarget.ANDROID_SETTINGS.packageName -> "Settings (Test)"
              else -> "Free Fire"
            }
          }

          if (isScreenActive && isGameInForeground) {
            val nowMs = SystemClock.elapsedRealtime()
            if (lastTickRealtimeMs == 0L) {
              lastTickRealtimeMs = nowMs
            } else {
              val deltaSeconds = (nowMs - lastTickRealtimeMs) / 1000L
              if (deltaSeconds >= 1L) {
                elapsedSeconds += deltaSeconds
                lastTickRealtimeMs += deltaSeconds * 1000L
              }
            }

            if (elapsedSeconds >= config.warningSeconds && !warningTriggered) {
              warningTriggered = true
              currentStatus = if (elapsedSeconds >= config.limitSeconds) SessionStatus.LIMIT_EXCEEDED else SessionStatus.WARNING
              triggerWarningAlert(config.vibrationEnabled, config.soundEnabled, activeGameName)
            } else if (elapsedSeconds >= config.limitSeconds && !limitTriggered) {
              limitTriggered = true
              currentStatus = SessionStatus.LIMIT_EXCEEDED
              triggerLimitAlert(config.vibrationEnabled, config.soundEnabled, activeGameName)
            } else {
              currentStatus = when {
                elapsedSeconds >= config.limitSeconds -> SessionStatus.LIMIT_EXCEEDED
                elapsedSeconds >= config.warningSeconds -> SessionStatus.WARNING
                else -> SessionStatus.RUNNING
              }
            }

            // Periodically persist last session & refresh system stats every 30s
            if (elapsedSeconds - lastStatsRefreshElapsed >= 30L) {
              lastStatsRefreshElapsed = elapsedSeconds
              SessionManager.recordSessionEnded(this@SessionTimerService, currentActivePackage, elapsedSeconds)
            }
          } else {
            // Screen is off/locked OR user exited the game to another app/Recents: pause immediately!
            lastTickRealtimeMs = 0L
            currentStatus = SessionStatus.PAUSED
          }
        } else {
          // Game was exited / swiped from Recents (or no game active)
          lastTickRealtimeMs = 0L
          if (currentActivePackage != null || stoppedBySwipe) {
            if (elapsedSeconds > 10 && currentActivePackage != null) {
              SessionManager.recordSessionEnded(this@SessionTimerService, currentActivePackage, elapsedSeconds)
            }
            elapsedSeconds = 0L
            warningTriggered = false
            limitTriggered = false
            lastStatsRefreshElapsed = 0L
            currentActivePackage = null
            activeGameName = "Free Fire"
            currentStatus = SessionStatus.IDLE
          } else if (currentStatus != SessionStatus.IDLE) {
            currentStatus = SessionStatus.IDLE
          }
        }

        SessionManager.updateFromService(
          elapsed = elapsedSeconds,
          status = currentStatus,
          warningHit = warningTriggered,
          limitHit = limitTriggered,
          foregroundPkg = topForegroundPkg,
          isTargetActive = isTargetActive,
          activePkg = currentActivePackage,
          gameDisplayName = activeGameName
        )

        updateOngoingNotification(currentStatus, isGameInForeground && isScreenActive, activeGameName)

        handler.postDelayed(this, 1000L)
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    createNotificationChannels()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START, null -> {
        if (!isServiceActive) {
          val currentState = SessionManager.state.value
          elapsedSeconds = currentState.elapsedSeconds
          warningTriggered = currentState.warningTriggered
          limitTriggered = currentState.limitTriggered
          lastTickRealtimeMs = 0L
          isServiceActive = true
          handler.removeCallbacks(monitorRunnable)
          handler.post(monitorRunnable)
        }

        val notification = buildOngoingNotification(SessionManager.state.value.status, false, activeGameName)
        startForegroundCompat(notification)
      }

      ACTION_RESET_SESSION -> {
        if (elapsedSeconds > 10 && currentActivePackage != null) {
          SessionManager.recordSessionEnded(this, currentActivePackage, elapsedSeconds)
        }
        elapsedSeconds = 0L
        warningTriggered = false
        limitTriggered = false
        lastStatsRefreshElapsed = 0L
        lastTickRealtimeMs = 0L
        currentActivePackage = null
        activeGameName = "Free Fire"
        UsageAccessHelper.resetForNewSession()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_WARNING)
        notificationManager.cancel(NOTIFICATION_ID_LIMIT)

        SessionManager.updateFromService(
          elapsed = 0L,
          status = SessionStatus.IDLE,
          warningHit = false,
          limitHit = false,
          foregroundPkg = null,
          isTargetActive = false,
          activePkg = null,
          gameDisplayName = "Free Fire"
        )

        if (!isServiceActive) {
          isServiceActive = true
        }
        handler.removeCallbacks(monitorRunnable)
        handler.postDelayed(monitorRunnable, 1000L)

        val notification = buildOngoingNotification(SessionStatus.IDLE, false, "Free Fire")
        startForegroundCompat(notification)
      }

      ACTION_STOP -> {
        if (elapsedSeconds > 10 && currentActivePackage != null) {
          SessionManager.recordSessionEnded(this, currentActivePackage, elapsedSeconds)
        }
        isServiceActive = false
        lastTickRealtimeMs = 0L
        handler.removeCallbacks(monitorRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }

      ACTION_UPDATE_CONFIG -> {
        val updatedConfig = SessionManager.state.value.config
        if (elapsedSeconds < updatedConfig.warningSeconds) {
          warningTriggered = false
        }
        if (elapsedSeconds < updatedConfig.limitSeconds) {
          limitTriggered = false
        }
        updateOngoingNotification(
          SessionManager.state.value.status,
          SessionManager.state.value.isTargetAppActive,
          activeGameName
        )
      }
    }

    return START_STICKY
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      } else {
        0
      }
      startForeground(NOTIFICATION_ID_ONGOING, notification, fgsType)
    } else {
      startForeground(NOTIFICATION_ID_ONGOING, notification)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    isServiceActive = false
    lastTickRealtimeMs = 0L
    handler.removeCallbacks(monitorRunnable)
    super.onDestroy()
  }

  private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

      val monitorChannel = NotificationChannel(
        CHANNEL_MONITOR,
        getString(R.string.session_service_channel_name),
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = getString(R.string.session_service_channel_desc)
        setShowBadge(false)
        enableLights(false)
        enableVibration(false)
      }

      val alertChannel = NotificationChannel(
        CHANNEL_ALERTS,
        getString(R.string.alert_channel_name),
        NotificationManager.IMPORTANCE_HIGH
      ).apply {
        description = getString(R.string.alert_channel_desc)
        setShowBadge(true)
        enableLights(true)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 800)
      }

      notificationManager.createNotificationChannel(monitorChannel)
      notificationManager.createNotificationChannel(alertChannel)
    }
  }

  /**
   * Builds the single clean notification without buttons, using the proper timer icon.
   */
  private fun buildOngoingNotification(
    status: SessionStatus,
    isTargetInForeground: Boolean,
    gameName: String
  ): Notification {
    val config = SessionManager.state.value.config
    val elapsedStr = SessionState.formatDuration(elapsedSeconds)
    val limitStr = SessionState.formatDuration(config.limitSeconds)
    val remainingSec = (config.limitSeconds - elapsedSeconds).coerceAtLeast(0L)
    val remainingStr = SessionState.formatDurationCompact(remainingSec)

    val title: String
    val contentText: String

    when (status) {
      SessionStatus.RUNNING -> {
        title = "🔥 $gameName: $elapsedStr / $limitStr"
        contentText = "$remainingStr left • Counting while playing"
      }
      SessionStatus.WARNING -> {
        title = "⚠️ 1h 40m Warning: $remainingStr left!"
        contentText = "$gameName • Wrap up match! 20m left in 2h limit"
      }
      SessionStatus.LIMIT_EXCEEDED -> {
        title = "🛑 2-Hour Limit Reached! ($elapsedStr)"
        contentText = "$gameName limit reached • Open app & tap End to reset"
      }
      SessionStatus.PAUSED -> {
        title = "⏸️ $gameName Paused: $elapsedStr"
        contentText = if (!UsageAccessHelper.isScreenInteractiveAndUnlocked(this)) {
          "Screen locked • Unlock to resume ($remainingStr left)"
        } else {
          "Auto-paused • Open game to resume or tap End to reset"
        }
      }
      SessionStatus.IDLE -> {
        title = "🛡️ FireLimit Auto-Monitor"
        contentText = "Launch Free Fire, MAX, or Advance to track 2h limit"
      }
    }

    val appIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      this,
      0,
      appIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_MONITOR)
      .setSmallIcon(R.drawable.ic_stat_timer)
      .setContentTitle(title)
      .setContentText(contentText)
      .setSubText("Session Limit")
      .setContentIntent(contentPendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)

    if (config.limitSeconds > 0 && elapsedSeconds > 0) {
      val progress = (elapsedSeconds * 100 / config.limitSeconds).toInt().coerceIn(0, 100)
      builder.setProgress(100, progress, false)
    }

    return builder.build()
  }

  private fun updateOngoingNotification(
    status: SessionStatus,
    isTargetInForeground: Boolean,
    gameName: String
  ) {
    val notification = buildOngoingNotification(status, isTargetInForeground, gameName)
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID_ONGOING, notification)
  }

  private fun triggerWarningAlert(vibrate: Boolean, sound: Boolean, gameName: String) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val appIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      this,
      5,
      appIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val alertBuilder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
      .setSmallIcon(R.drawable.ic_stat_timer)
      .setContentTitle("⚠️ $gameName: 1h 40m Warning!")
      .setContentText("You have 20 minutes left before your 2-hour session limit. Finish your match!")
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText("⚠️ 1 Hour 40 Minutes Warning!\n\nYou have 20 minutes remaining in your 2-hour $gameName session limit. Finish your current match and prepare to take a break.")
      )
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(true)
      .setContentIntent(contentPendingIntent)

    if (sound) {
      val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      alertBuilder.setSound(alarmSound)
    }

    if (vibrate) {
      vibrateDevice(longArrayOf(0, 500, 250, 500, 250, 800))
    }

    notificationManager.notify(NOTIFICATION_ID_WARNING, alertBuilder.build())
  }

  private fun triggerLimitAlert(vibrate: Boolean, sound: Boolean, gameName: String) {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val appIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent = PendingIntent.getActivity(
      this,
      6,
      appIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val alertBuilder = NotificationCompat.Builder(this, CHANNEL_ALERTS)
      .setSmallIcon(R.drawable.ic_stat_timer)
      .setContentTitle("🛑 $gameName: 2-Hour Limit Reached!")
      .setContentText("Session limit completed. Step away, stretch, and rest your eyes!")
      .setStyle(
        NotificationCompat.BigTextStyle()
          .bigText("🛑 2-Hour $gameName Limit Reached!\n\nYou have completed your 2-hour gaming session. Take a healthy break.")
      )
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setAutoCancel(true)
      .setContentIntent(contentPendingIntent)

    if (sound) {
      val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      alertBuilder.setSound(alarmSound)
    }

    if (vibrate) {
      vibrateDevice(longArrayOf(0, 600, 200, 600, 200, 1000))
    }

    notificationManager.notify(NOTIFICATION_ID_LIMIT, alertBuilder.build())
  }

  private fun vibrateDevice(pattern: LongArray) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        val vibrator = vibratorManager?.defaultVibrator
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
      } else {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
          @Suppress("DEPRECATION")
          vibrator?.vibrate(pattern, -1)
        }
      }
    } catch (_: Exception) { }
  }
}

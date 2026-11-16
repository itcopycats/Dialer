package com.example.util

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import com.example.model.GameTarget
import com.example.model.GameUsageRecord
import java.util.Calendar

data class TrackingSnapshot(
  val activeSessionPackage: String?,
  val topForegroundPackage: String?,
  val isGameInForeground: Boolean = false,
  val sessionStoppedByRecentsSwipe: Boolean = false
)

object UsageAccessHelper {

  val FREE_FIRE_PACKAGES = setOf(
    "com.dts.freefireth",  // Free Fire Normal
    "com.dts.freefiremax", // Free Fire MAX
    "com.dts.freefireadv", // Free Fire Advance
    "com.dts.freefirein"   // Free Fire India
  )

  private const val EVENT_ACTIVITY_PAUSED = 2    // UsageEvents.Event.ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
  private const val EVENT_ACTIVITY_STOPPED = 23  // UsageEvents.Event.ACTIVITY_STOPPED
  private const val EVENT_ACTIVITY_DESTROYED = 24 // UsageEvents.Event.ACTIVITY_DESTROYED (@hide in SDK)
  private const val STOP_PAUSE_GRACE_MS = 1_500L
  private const val LAUNCHER_PAUSE_GRACE_MS = 2_500L
  private const val INITIAL_LOOKBACK_MS = 15_000L

  private var lastProcessedEventTime: Long = 0L
  private var lastTopForegroundPkg: String? = null
  private var activeSessionPkg: String? = null
  private var activeForegroundActivity: String? = null
  private val aliveGameActivities = mutableSetOf<String>()
  private var pendingStopTimestamp: Long = 0L
  private var leftToHomeTimestamp: Long = 0L
  private var isPausedByExit: Boolean = false
  private var cachedHomeLaunchers: Set<String>? = null

  @Synchronized
  fun resetForegroundTracking() {
    lastProcessedEventTime = 0L
    lastTopForegroundPkg = null
    activeSessionPkg = null
    activeForegroundActivity = null
    aliveGameActivities.clear()
    pendingStopTimestamp = 0L
    leftToHomeTimestamp = 0L
    isPausedByExit = false
  }

  /**
   * Resets active tracking state while advancing lastProcessedEventTime to now so
   * previously logged game events cannot re-trigger a session after tapping End.
   */
  @Synchronized
  fun resetForNewSession() {
    lastProcessedEventTime = System.currentTimeMillis()
    lastTopForegroundPkg = null
    activeSessionPkg = null
    activeForegroundActivity = null
    aliveGameActivities.clear()
    pendingStopTimestamp = 0L
    leftToHomeTimestamp = 0L
    isPausedByExit = false
  }

  private fun isHomeLauncherPackage(context: Context, packageName: String?): Boolean {
    if (packageName == null) return false
    val launchers = cachedHomeLaunchers ?: run {
      val resolved = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
          .mapNotNull { it.activityInfo?.packageName }
          .toSet()
      } catch (_: Exception) {
        emptySet()
      }
      cachedHomeLaunchers = resolved
      resolved
    }
    return launchers.contains(packageName) ||
      packageName.contains("launcher", ignoreCase = true) ||
      packageName.endsWith(".home", ignoreCase = true)
  }

  fun hasUsageAccessPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
      )
    } else {
      @Suppress("DEPRECATION")
      appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
      )
    }
    return mode == AppOpsManager.MODE_ALLOWED
  }

  fun openUsageAccessSettings(context: Context) {
    try {
      val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
      context.startActivity(intent)
    } catch (_: Exception) {
      try {
        val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
      } catch (_: Exception) {
        val generalSettings = Intent(Settings.ACTION_SETTINGS).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(generalSettings)
      }
    }
  }

  fun isAppInstalled(context: Context, packageName: String): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  fun getAppIcon(context: Context, packageName: String): Drawable? {
    return try {
      context.packageManager.getApplicationIcon(packageName)
    } catch (_: Exception) {
      null
    }
  }

  fun launchGame(context: Context, packageName: String, displayName: String? = null) {
    val resolvedName = displayName ?: when (packageName) {
      GameTarget.FREE_FIRE_NORMAL.packageName -> GameTarget.FREE_FIRE_NORMAL.displayName
      GameTarget.FREE_FIRE_MAX.packageName -> GameTarget.FREE_FIRE_MAX.displayName
      GameTarget.FREE_FIRE_ADVANCE.packageName -> GameTarget.FREE_FIRE_ADVANCE.displayName
      GameTarget.ANDROID_SETTINGS.packageName -> GameTarget.ANDROID_SETTINGS.displayName
      else -> "Free Fire"
    }

    val pm = context.packageManager
    val launchIntent = pm.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(launchIntent)
      Toast.makeText(context, "Opened $resolvedName", Toast.LENGTH_SHORT).show()
    } else if (packageName == GameTarget.ANDROID_SETTINGS.packageName) {
      val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(settingsIntent)
      Toast.makeText(context, "Opened $resolvedName", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(context, "$resolvedName not installed", Toast.LENGTH_SHORT).show()
      try {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(marketIntent)
      } catch (_: Exception) {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
      }
    }
  }

  fun isScreenInteractiveAndUnlocked(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val isScreenOn = powerManager?.isInteractive ?: true
    if (!isScreenOn) return false

    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val isLocked = keyguardManager?.isKeyguardLocked ?: false
    return !isLocked
  }

  /**
   * Tracks foreground state accurately without auto-resetting the timer:
   * - Keeps counting during SystemUI overlays and Floating Window / Split-Screen apps
   *   because the game remains visible and does not receive ACTIVITY_STOPPED.
   * - Auto-pauses within ~3 seconds when the user exits the game (to Home, Recents,
   *   or another fullscreen app) while preserving elapsed time.
   * - Auto-resumes immediately when the user enters the game again.
   * - Only the in-app End button resets the timer to 00:00:00.
   */
  @Synchronized
  fun pollTrackingSnapshot(context: Context, includeSettings: Boolean): TrackingSnapshot {
    if (!hasUsageAccessPermission(context)) {
      resetForegroundTracking()
      return TrackingSnapshot(activeSessionPackage = null, topForegroundPackage = null, isGameInForeground = false)
    }

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
      ?: return TrackingSnapshot(
        activeSessionPackage = activeSessionPkg,
        topForegroundPackage = lastTopForegroundPkg,
        isGameInForeground = activeSessionPkg != null && !isPausedByExit
      )

    val now = System.currentTimeMillis()
    val queryStart = if (lastProcessedEventTime == 0L) {
      now - INITIAL_LOOKBACK_MS
    } else {
      (lastProcessedEventTime - 2_000L).coerceAtLeast(now - INITIAL_LOOKBACK_MS)
    }

    val resumeEventType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      UsageEvents.Event.ACTIVITY_RESUMED
    } else {
      @Suppress("DEPRECATION")
      UsageEvents.Event.MOVE_TO_FOREGROUND
    }

    try {
      val events = usageStatsManager.queryEvents(queryStart, now)
      val event = UsageEvents.Event()
      var maxSeenTime = lastProcessedEventTime

      while (events.hasNextEvent()) {
        events.getNextEvent(event)
        if (event.timeStamp <= lastProcessedEventTime) continue
        if (event.timeStamp > maxSeenTime) {
          maxSeenTime = event.timeStamp
        }

        val pkg = event.packageName ?: continue
        if (pkg == "com.android.systemui") continue

        val activityKey = event.className ?: "__default__"

        when (event.eventType) {
          resumeEventType -> {
            lastTopForegroundPkg = pkg
            if (isMonitoredPackage(pkg, includeSettings)) {
              if (activeSessionPkg != pkg) {
                activeSessionPkg = pkg
                aliveGameActivities.clear()
              }
              activeForegroundActivity = activityKey
              aliveGameActivities.add(activityKey)
              pendingStopTimestamp = 0L
              leftToHomeTimestamp = 0L
              isPausedByExit = false
            } else if (activeSessionPkg != null && !isPausedByExit) {
              val isExitTarget = pkg == context.packageName ||
                isHomeLauncherPackage(context, pkg) ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
              if (isExitTarget && leftToHomeTimestamp == 0L) {
                leftToHomeTimestamp = event.timeStamp
              }
            }
          }
          EVENT_ACTIVITY_PAUSED -> {
            // Do NOT pause on ACTIVITY_PAUSED alone: SystemUI and Floating Windows trigger
            // ACTIVITY_PAUSED on the game while the game remains visible underneath.
          }
          EVENT_ACTIVITY_STOPPED -> {
            if (pkg == activeSessionPkg && (activityKey == activeForegroundActivity || aliveGameActivities.size <= 1)) {
              if (pendingStopTimestamp == 0L) {
                pendingStopTimestamp = event.timeStamp
              }
            }
          }
          EVENT_ACTIVITY_DESTROYED -> {
            if (pkg == activeSessionPkg) {
              aliveGameActivities.remove(activityKey)
              val isExitTarget = lastTopForegroundPkg == context.packageName ||
                isHomeLauncherPackage(context, lastTopForegroundPkg)
              if (aliveGameActivities.isEmpty() || activityKey == activeForegroundActivity || isExitTarget) {
                if (pendingStopTimestamp == 0L) {
                  pendingStopTimestamp = event.timeStamp
                }
              }
            }
          }
        }
      }

      if (maxSeenTime > lastProcessedEventTime) {
        lastProcessedEventTime = maxSeenTime
      }
    } catch (_: Exception) { }

    val isScreenActive = isScreenInteractiveAndUnlocked(context)
    if (!isScreenActive && activeSessionPkg != null) {
      // While screen is locked/off, do not advance exit-pause timers
      pendingStopTimestamp = 0L
      leftToHomeTimestamp = 0L
    }

    if (activeSessionPkg != null && !isPausedByExit && isScreenActive) {
      val stoppedLongEnough = pendingStopTimestamp > 0L && (now - pendingStopTimestamp) >= STOP_PAUSE_GRACE_MS
      val leftToHomeLongEnough = leftToHomeTimestamp > 0L && (now - leftToHomeTimestamp) >= LAUNCHER_PAUSE_GRACE_MS

      if (stoppedLongEnough || leftToHomeLongEnough) {
        isPausedByExit = true
        pendingStopTimestamp = 0L
        leftToHomeTimestamp = 0L
      }
    }

    val isGameInForeground = activeSessionPkg != null && !isPausedByExit

    return TrackingSnapshot(
      activeSessionPackage = activeSessionPkg,
      topForegroundPackage = lastTopForegroundPkg,
      isGameInForeground = isGameInForeground,
      sessionStoppedByRecentsSwipe = false
    )
  }

  fun isMonitoredPackage(packageName: String?, includeSettings: Boolean): Boolean {
    if (packageName == null) return false
    if (FREE_FIRE_PACKAGES.contains(packageName)) return true
    if (includeSettings && packageName == "com.android.settings") return true
    return false
  }

  /**
   * Recovers historical usage stats from Android's UsageStatsManager for the given package.
   * Works on first install to recover all past permanent usage recorded by Android OS.
   */
  fun fetchUsageStatsForPackage(
    context: Context,
    target: GameTarget,
    lastSessionDuration: Long = 0L
  ): GameUsageRecord {
    val isInstalled = isAppInstalled(context, target.packageName)
    if (!hasUsageAccessPermission(context)) {
      return GameUsageRecord(
        target = target,
        isInstalled = isInstalled,
        lastSessionSeconds = lastSessionDuration
      )
    }

    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
      ?: return GameUsageRecord(target = target, isInstalled = isInstalled, lastSessionSeconds = lastSessionDuration)

    val now = System.currentTimeMillis()

    // 1. Today's start timestamp (midnight)
    val cal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val todayStart = cal.timeInMillis

    // 2. Week's start timestamp
    val weekCal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
      set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
    }
    val weekStart = weekCal.timeInMillis

    // 3. Month's start timestamp
    val monthCal = Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
      set(Calendar.DAY_OF_MONTH, 1)
    }
    val monthStart = monthCal.timeInMillis

    // 4. All-time start timestamp (3 years back)
    val yearCal = Calendar.getInstance().apply {
      add(Calendar.YEAR, -3)
    }
    val allTimeStart = yearCal.timeInMillis

    var todaySec = 0L
    var weekSec = 0L
    var monthSec = 0L
    var totalSec = 0L

    try {
      // Query Day (filter to buckets active today)
      val dailyStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, todayStart, now)
      todaySec = dailyStats
        ?.filter { it.packageName == target.packageName && it.lastTimeUsed >= todayStart }
        ?.sumOf { it.totalTimeInForeground / 1000L } ?: 0L

      // Query Week
      val weeklyStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, weekStart, now)
      weekSec = weeklyStats
        ?.filter { it.packageName == target.packageName && it.lastTimeUsed >= weekStart }
        ?.sumOf { it.totalTimeInForeground / 1000L } ?: 0L

      // Query Month
      val monthlyStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, monthStart, now)
      monthSec = monthlyStats
        ?.filter { it.packageName == target.packageName && it.lastTimeUsed >= monthStart }
        ?.sumOf { it.totalTimeInForeground / 1000L } ?: 0L

      // Query All-time / Yearly
      val yearlyStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, allTimeStart, now)
      totalSec = yearlyStats
        ?.filter { it.packageName == target.packageName }
        ?.sumOf { it.totalTimeInForeground / 1000L } ?: 0L

      // Guarantee proper hierarchy if system buckets differ
      if (weekSec < todaySec) weekSec = todaySec
      if (monthSec < weekSec) monthSec = weekSec
      if (totalSec < monthSec) totalSec = monthSec
    } catch (_: Exception) { }

    return GameUsageRecord(
      target = target,
      isInstalled = isInstalled,
      lastSessionSeconds = lastSessionDuration,
      todaySeconds = todaySec,
      weekSeconds = weekSec,
      monthSeconds = monthSec,
      totalSeconds = totalSec
    )
  }
}

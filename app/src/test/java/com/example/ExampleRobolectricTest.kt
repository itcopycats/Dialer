package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.GameTarget
import com.example.model.GameUsageRecord
import com.example.model.SessionConfig
import com.example.model.SessionState
import com.example.model.SessionStatus
import com.example.util.UsageAccessHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("FireLimit", appName)
  }

  @Test
  fun `verify default 2 hour limit and 1 hour 40 min warning`() {
    val config = SessionConfig()
    assertEquals(7200L, config.limitSeconds)       // 2 hours
    assertEquals(6000L, config.warningSeconds)     // 1 hour 40 minutes

    val stateAt1h40m = SessionState(
      elapsedSeconds = 6000L,
      config = config,
      status = SessionStatus.WARNING
    )

    assertEquals("01:40:00", stateAt1h40m.formattedElapsed)
    assertEquals("00:20:00", stateAt1h40m.formattedRemaining)
    assertEquals("20m", SessionState.formatDurationCompact(stateAt1h40m.remainingSeconds))
    assertTrue(stateAt1h40m.progressFraction > 0.83f)
  }

  @Test
  fun `verify 2 hour limit exceeded state`() {
    val config = SessionConfig()
    val stateAt2h = SessionState(
      elapsedSeconds = 7200L,
      config = config,
      status = SessionStatus.LIMIT_EXCEEDED
    )

    assertEquals("02:00:00", stateAt2h.formattedElapsed)
    assertEquals("00:00:00", stateAt2h.formattedRemaining)
    assertEquals(0L, stateAt2h.remainingSeconds)
    assertEquals(1.0f, stateAt2h.progressFraction, 0.001f)
  }

  @Test
  fun `verify free fire packages detection including max and advance`() {
    assertTrue(UsageAccessHelper.isMonitoredPackage("com.dts.freefireth", false))
    assertTrue(UsageAccessHelper.isMonitoredPackage("com.dts.freefiremax", false))
    assertTrue(UsageAccessHelper.isMonitoredPackage("com.dts.freefireadv", false))
    assertTrue(UsageAccessHelper.isMonitoredPackage("com.dts.freefirein", false))
    assertTrue(UsageAccessHelper.isMonitoredPackage("com.android.settings", true))
    assertFalse(UsageAccessHelper.isMonitoredPackage("com.random.othergame", true))
  }

  @Test
  fun `verify game usage record duration formatting`() {
    val record = GameUsageRecord(
      target = GameTarget.FREE_FIRE_NORMAL,
      lastSessionSeconds = 2700L, // 45m
      todaySeconds = 5400L,       // 1h 30m
      weekSeconds = 18000L,       // 5h
      monthSeconds = 72000L,      // 20h
      totalSeconds = 144000L      // 40h
    )

    assertEquals("45m", record.formattedLastSession)
    assertEquals("1h 30m", record.formattedToday)
    assertEquals("5h", record.formattedWeek)
    assertEquals("20h", record.formattedMonth)
    assertEquals("40h", record.formattedTotal)
  }

  @Test
  fun `verify foreground tracking resets cleanly when permission missing`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    UsageAccessHelper.resetForegroundTracking()
    // Without usage access granted in Robolectric test environment, pollTrackingSnapshot returns null safely
    val snapshot = UsageAccessHelper.pollTrackingSnapshot(context, includeSettings = false)
    assertNull(snapshot.activeSessionPackage)
    assertNull(snapshot.topForegroundPackage)
    assertFalse(snapshot.isGameInForeground)
    assertFalse(snapshot.sessionStoppedByRecentsSwipe)
    assertTrue(UsageAccessHelper.isScreenInteractiveAndUnlocked(context))
  }

  @Test
  fun `verify package name is firelimit bypass`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("firelimit.bypass", context.packageName)
  }

  @Test
  fun `verify endSessionAndReset resets timer and keeps state idle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.service.SessionManager.updateFromService(
      elapsed = 1234L,
      status = SessionStatus.RUNNING,
      warningHit = false,
      limitHit = false,
      foregroundPkg = "com.dts.freefireth",
      isTargetActive = true,
      activePkg = "com.dts.freefireth",
      gameDisplayName = "Free Fire"
    )
    assertEquals(1234L, com.example.service.SessionManager.state.value.elapsedSeconds)

    com.example.service.SessionManager.endSessionAndReset(context)

    val resetState = com.example.service.SessionManager.state.value
    assertEquals(0L, resetState.elapsedSeconds)
    assertEquals(SessionStatus.IDLE, resetState.status)
    assertFalse(resetState.isTargetAppActive)
    assertNull(resetState.activeGamePackage)
  }

  @Test
  fun `verify prepareShareApkIntent builds share chooser intent`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fakeApk = java.io.File(context.cacheDir, "test_base.apk").apply {
      writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
    }
    context.applicationInfo.publicSourceDir = fakeApk.absolutePath
    context.applicationInfo.sourceDir = fakeApk.absolutePath

    val result = com.example.util.ApkExportHelper.prepareShareApkIntent(context)
    assertTrue(result.isSuccess)
    val chooser = result.getOrNull()
    assertEquals(android.content.Intent.ACTION_CHOOSER, chooser?.action)
  }

  @Test
  fun `verify launchGame settings shows Opened toast`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    UsageAccessHelper.launchGame(context, GameTarget.ANDROID_SETTINGS.packageName, GameTarget.ANDROID_SETTINGS.displayName)
    assertEquals("Opened Android Settings", ShadowToast.getTextOfLatestToast())
  }
}

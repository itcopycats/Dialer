package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.components.GameCard
import com.example.ui.components.NotificationPermissionCard
import com.example.ui.components.SessionGauge
import com.example.ui.components.SessionSettingsDialog
import com.example.ui.components.UsageAccessCard
import com.example.ui.components.WarningBanner
import com.example.ui.theme.FlameCrimson
import com.example.ui.theme.FlameGold
import com.example.ui.theme.FlameOrange
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SessionViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: SessionViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      val sessionState by viewModel.sessionState.collectAsState()
      MyApplicationTheme(
        themeMode = sessionState.config.themeMode,
        useAmoledPureBlack = sessionState.config.useAmoledPureBlack
      ) {
        FireLimitApp(
          viewModel = viewModel,
          onEndAndClose = {
            viewModel.endSessionAndReset()
            finishAndRemoveTask()
          }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    viewModel.refreshPermission()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FireLimitApp(
  viewModel: SessionViewModel,
  onEndAndClose: () -> Unit = { viewModel.endSessionAndReset() }
) {
  val sessionState by viewModel.sessionState.collectAsState()
  var showSettingsDialog by remember { mutableStateOf(false) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermission()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
          titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Box(
              modifier = Modifier
                .size(36.dp)
                .background(FlameOrange.copy(alpha = 0.2f), CircleShape),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = FlameOrange,
                modifier = Modifier.size(22.dp)
              )
            }

            Column {
              Text(
                text = "FireLimit",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Free Fire • MAX • Advance • 2h Limit",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
              )
            }
          }
        },
        actions = {
          IconButton(
            onClick = { showSettingsDialog = true },
            modifier = Modifier.testTag("settings_button")
          ) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Session Settings",
              tint = MaterialTheme.colorScheme.onSurface
            )
          }
        }
      )
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .background(MaterialTheme.colorScheme.background),
      contentAlignment = Alignment.TopCenter
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 600.dp)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Usage Access Status & Setup
        UsageAccessCard(
          state = sessionState,
          onGrantPermission = { viewModel.openUsageAccessSettings() },
          onToggleTestWithSettings = { enabled -> viewModel.toggleTestWithSettings(enabled) }
        )

        // Notification Permission Reminder (Android 13+)
        NotificationPermissionCard()

        // Dynamic Warning Banner (1h 40m warning and 2h limit reached)
        WarningBanner(state = sessionState)

        // Active Session Gauge (2h limit progress with 1h 40m warning notch)
        SessionGauge(state = sessionState)

        // End Session Button: resets timer, closes app, keeps auto-monitor ready for next game launch
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, FlameCrimson.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = onEndAndClose,
              colors = ButtonDefaults.buttonColors(
                containerColor = FlameCrimson,
                contentColor = Color.White
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("end_session_button")
            ) {
              Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.end_button_label),
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
              )
            }

            Text(
              text = stringResource(R.string.end_button_helper),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 11.sp,
              textAlign = TextAlign.Center
            )
          }
        }

        // Section: Game Launch Shortcuts & Permanent Usage Stats
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = null,
                tint = FlameOrange,
                modifier = Modifier.size(20.dp)
              )
              Text(
                text = "GAMES & PERMANENT PLAYED TIME",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
              )
            }

            Text(
              text = "Recovered from System",
              color = FlameGold,
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          // Render Game Cards with real installed icons, launch shortcuts, and usage breakdown
          sessionState.gameUsageRecords.forEach { record ->
            val isCurrentActive = sessionState.isTargetAppActive &&
                sessionState.activeGamePackage == record.target.packageName

            GameCard(
              record = record,
              isCurrentlyActive = isCurrentActive,
              onLaunch = { viewModel.launchGame(record.target.packageName, record.target.displayName) }
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }

  if (showSettingsDialog) {
    SessionSettingsDialog(
      currentConfig = sessionState.config,
      onDismiss = { showSettingsDialog = false },
      onSave = { updatedConfig ->
        viewModel.updateConfig(updatedConfig)
        showSettingsDialog = false
      }
    )
  }
}

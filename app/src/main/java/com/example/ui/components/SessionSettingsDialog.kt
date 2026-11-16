package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.SessionConfig
import com.example.ui.theme.FlameCrimson
import com.example.ui.theme.FlameGold
import com.example.ui.theme.FlameOrange
import com.example.ui.theme.SafeGreen
import com.example.util.ApkExportHelper
import kotlinx.coroutines.launch

@Composable
fun SessionSettingsDialog(
  currentConfig: SessionConfig,
  onDismiss: () -> Unit,
  onSave: (SessionConfig) -> Unit
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  var selectedLimitSec by remember { mutableStateOf(currentConfig.limitSeconds) }
  var selectedWarningSec by remember { mutableStateOf(currentConfig.warningSeconds) }
  var vibrateEnabled by remember { mutableStateOf(currentConfig.vibrationEnabled) }
  var soundEnabled by remember { mutableStateOf(currentConfig.soundEnabled) }
  var themeMode by remember { mutableStateOf(currentConfig.themeMode) }
  var amoledPureBlack by remember { mutableStateOf(currentConfig.useAmoledPureBlack) }

  var isExporting by remember { mutableStateOf(false) }
  var exportStatusMessage by remember { mutableStateOf<String?>(null) }
  var isExportError by remember { mutableStateOf(false) }

  val performSaveApk: () -> Unit = {
    if (!isExporting) {
      isExporting = true
      exportStatusMessage = "Saving APK to internal storage..."
      isExportError = false
      coroutineScope.launch {
        val result = ApkExportHelper.saveApkToInternalStorage(context)
        isExporting = false
        result.onSuccess { savedPath ->
          isExportError = false
          exportStatusMessage = "Saved to: $savedPath"
          Toast.makeText(context, "APK saved to $savedPath", Toast.LENGTH_LONG).show()
        }.onFailure { err ->
          isExportError = true
          val msg = err.message ?: "Failed to save APK"
          exportStatusMessage = msg
          Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { _ ->
    performSaveApk()
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Settings,
          contentDescription = null,
          tint = FlameOrange,
          modifier = Modifier.size(24.dp)
        )
        Text(text = "Session & Display Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Theme & AMOLED Dark Mode Section
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.DarkMode, contentDescription = null, tint = FlameOrange, modifier = Modifier.size(16.dp))
            Text(text = "Dark Mode & AMOLED:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            val modes = listOf(
              "AUTO" to "Auto (System)",
              "AMOLED" to "AMOLED Pure",
              "LIGHT" to "Light"
            )

            modes.forEach { (mode, label) ->
              FilterChip(
                selected = themeMode == mode,
                onClick = { themeMode = mode },
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = FlameOrange.copy(alpha = 0.25f),
                  selectedLabelColor = FlameOrange
                )
              )
            }
          }

          // True Pitch Black AMOLED toggle
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(text = "AMOLED Pure Black (#000000)", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              Text(text = "Turns off OLED screen pixels to save battery while gaming", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Switch(
              checked = amoledPureBlack,
              onCheckedChange = { amoledPureBlack = it },
              colors = SwitchDefaults.colors(
                checkedThumbColor = SafeGreen,
                checkedTrackColor = SafeGreen.copy(alpha = 0.3f)
              )
            )
          }
        }

        // Limit Selection
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.Timer, contentDescription = null, tint = FlameOrange, modifier = Modifier.size(16.dp))
            Text(text = "Max Session Limit:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            val limits = listOf(
              5400L to "1.5h",
              7200L to "2h (Default)",
              9000L to "2.5h",
              10800L to "3h"
            )

            limits.forEach { (sec, label) ->
              FilterChip(
                selected = selectedLimitSec == sec,
                onClick = {
                  selectedLimitSec = sec
                  if (selectedWarningSec >= sec) {
                    selectedWarningSec = sec - 1200L
                  }
                },
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = FlameOrange.copy(alpha = 0.25f),
                  selectedLabelColor = FlameOrange
                )
              )
            }
          }
        }

        // Warning Selection
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = FlameGold, modifier = Modifier.size(16.dp))
            Text(text = "Warning Alert Time:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            val warnings = listOf(
              5400L to "1h 30m",
              6000L to "1h 40m (Default)",
              6600L to "1h 50m"
            )

            warnings.forEach { (sec, label) ->
              FilterChip(
                selected = selectedWarningSec == sec,
                onClick = { selectedWarningSec = sec },
                label = { Text(label, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = FlameGold.copy(alpha = 0.25f),
                  selectedLabelColor = FlameGold
                )
              )
            }
          }
        }

        // Vibrate Toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Column {
              Text(text = "Vibration Alert", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              Text(text = "Vibrate at 1h 40m & 2h limit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
          }
          Switch(
            checked = vibrateEnabled,
            onCheckedChange = { vibrateEnabled = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = FlameOrange,
              checkedTrackColor = FlameOrange.copy(alpha = 0.3f)
            )
          )
        }

        // Sound Toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Column {
              Text(text = "Alarm Sound", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              Text(text = "Play audible chime on warning & limit", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
          }
          Switch(
            checked = soundEnabled,
            onCheckedChange = { soundEnabled = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = FlameOrange,
              checkedTrackColor = FlameOrange.copy(alpha = 0.3f)
            )
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        // App Share & Save APK Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.Share, contentDescription = null, tint = FlameGold, modifier = Modifier.size(16.dp))
            Text(
              text = "App Share & APK Backup:",
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.SemiBold,
              fontSize = 13.sp
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = {
                if (!isExporting) {
                  isExporting = true
                  exportStatusMessage = "Preparing APK to share..."
                  isExportError = false
                  coroutineScope.launch {
                    val result = ApkExportHelper.prepareShareApkIntent(context)
                    isExporting = false
                    result.onSuccess { chooserIntent ->
                      exportStatusMessage = null
                      context.startActivity(chooserIntent)
                    }.onFailure { err ->
                      isExportError = true
                      val msg = err.message ?: "Failed to share APK"
                      exportStatusMessage = msg
                      Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                  }
                }
              },
              enabled = !isExporting,
              shape = RoundedCornerShape(10.dp),
              border = BorderStroke(1.dp, FlameOrange.copy(alpha = 0.7f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = FlameOrange),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("share_app_button")
            ) {
              Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share App APK",
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Share App", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
              onClick = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                  ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                  ) != PackageManager.PERMISSION_GRANTED
                ) {
                  legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                  performSaveApk()
                }
              },
              enabled = !isExporting,
              shape = RoundedCornerShape(10.dp),
              border = BorderStroke(1.dp, SafeGreen.copy(alpha = 0.7f)),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = SafeGreen),
              modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("save_apk_button")
            ) {
              Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Save APK to Internal Storage",
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Save APK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }

          exportStatusMessage?.let { msg ->
            Text(
              text = msg,
              color = if (isExportError) FlameCrimson else SafeGreen,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium,
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  color = (if (isExportError) FlameCrimson else SafeGreen).copy(alpha = 0.1f),
                  shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            currentConfig.copy(
              limitSeconds = selectedLimitSec,
              warningSeconds = selectedWarningSec,
              vibrationEnabled = vibrateEnabled,
              soundEnabled = soundEnabled,
              themeMode = themeMode,
              useAmoledPureBlack = amoledPureBlack
            )
          )
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = FlameOrange,
          contentColor = Color.Black
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.testTag("save_settings_button")
      ) {
        Text("Save Settings", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
      ) {
        Text("Cancel")
      }
    }
  )
}

package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SessionState
import com.example.ui.theme.FlameGold
import com.example.ui.theme.SafeGreen
import com.example.ui.theme.SurfaceBorderDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun UsageAccessCard(
  state: SessionState,
  onGrantPermission: () -> Unit,
  onToggleTestWithSettings: (Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  val hasPermission = state.hasUsageAccessPermission

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (hasPermission) SafeGreen.copy(alpha = 0.4f) else FlameGold.copy(alpha = 0.6f)
    ),
    modifier = modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Header: Usage Access Status
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(32.dp)
              .background(
                if (hasPermission) SafeGreen.copy(alpha = 0.2f) else FlameGold.copy(alpha = 0.2f),
                CircleShape
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (hasPermission) Icons.Default.CheckCircle else Icons.Default.Security,
              contentDescription = null,
              tint = if (hasPermission) SafeGreen else FlameGold,
              modifier = Modifier.size(20.dp)
            )
          }

          Column {
            Text(
              text = "System Usage Access",
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (hasPermission) "Permission Active • Permanent History Recovered" else "Permission Required for Auto-Tracking",
              color = if (hasPermission) SafeGreen else FlameGold,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }

      // If Permission Not Granted: Show Call to Action Button
      if (!hasPermission) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(FlameGold.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, FlameGold.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Grant Usage Access to allow FireLimit to recover your past played time and automatically monitor your 2-hour session when launching Free Fire.",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp
          )

          Button(
            onClick = onGrantPermission,
            colors = ButtonDefaults.buttonColors(
              containerColor = FlameGold,
              contentColor = Color.Black
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("grant_usage_access_button")
          ) {
            Icon(
              imageVector = Icons.Default.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Grant Usage Access in Settings", fontWeight = FontWeight.Bold)
          }
        }
      } else {
        // Permission IS Granted: Show Live Detection Radar & Testing Option
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          // Live Foreground App Radar
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Radar,
                contentDescription = null,
                tint = if (state.isTargetAppActive) SafeGreen else FlameGold,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "ACTIVE FOREGROUND DETECTOR:",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
              )
            }

            val detectedDisplay = when {
              state.isTargetAppActive && state.status == com.example.model.SessionStatus.PAUSED ->
                "⏸️ ${state.activeGameDisplayName} (Auto-Paused • Re-open game to resume)"
              state.isTargetAppActive ->
                "🔥 ${state.activeGameDisplayName} (Active • Counting)"
              state.detectedForegroundPackage != null -> state.detectedForegroundPackage
              else -> "Waiting for game launch..."
            }

            Text(
              text = detectedDisplay,
              color = if (state.isTargetAppActive) SafeGreen else MaterialTheme.colorScheme.onSurface,
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium,
              fontFamily = FontFamily.Monospace
            )
          }

          // Settings App Tracking Option
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Test with Android Settings App",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
              )
              Text(
                text = "Track Android Settings app to verify auto-detection",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
              )
            }
            Switch(
              checked = state.config.includeSettingsForTesting,
              onCheckedChange = onToggleTestWithSettings,
              colors = SwitchDefaults.colors(
                checkedThumbColor = FlameGold,
                checkedTrackColor = FlameGold.copy(alpha = 0.3f)
              )
            )
          }
        }
      }
    }
  }
}

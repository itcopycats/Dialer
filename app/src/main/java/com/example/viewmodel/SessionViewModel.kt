package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.model.SessionConfig
import com.example.model.SessionState
import com.example.service.SessionManager
import com.example.util.UsageAccessHelper
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(application: Application) : AndroidViewModel(application) {

  val sessionState: StateFlow<SessionState> = SessionManager.state

  init {
    SessionManager.init(application.applicationContext)
  }

  fun refreshPermission() {
    SessionManager.refreshPermission(getApplication())
  }

  fun openUsageAccessSettings() {
    UsageAccessHelper.openUsageAccessSettings(getApplication())
  }

  fun launchGame(packageName: String, displayName: String? = null) {
    UsageAccessHelper.launchGame(getApplication(), packageName, displayName)
  }

  fun toggleTestWithSettings(enabled: Boolean) {
    val currentConfig = sessionState.value.config
    updateConfig(currentConfig.copy(includeSettingsForTesting = enabled))
  }

  fun updateConfig(config: SessionConfig) {
    SessionManager.updateConfig(getApplication(), config)
  }

  fun endSessionAndReset() {
    SessionManager.endSessionAndReset(getApplication())
  }
}

package com.shizington.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.BuildConfig
import com.shizington.tv.data.model.AccountInfo
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.main.TopBarHeight
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var account by mutableStateOf<AccountInfo?>(null)
        private set
    var accountError by mutableStateOf<String?>(null)
        private set
    var liveFormat by mutableStateOf(c.credentialsStore.liveFormat)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    val server get() = c.xtream.credentials?.server.orEmpty()
    val username get() = c.xtream.credentials?.username.orEmpty()

    init { refreshAccount() }

    private fun refreshAccount() {
        viewModelScope.launch {
            try {
                account = c.xtream.authenticate()
            } catch (e: Exception) {
                accountError = e.message
            }
        }
    }

    fun toggleLiveFormat() {
        liveFormat = if (liveFormat == "ts") "m3u8" else "ts"
        c.credentialsStore.liveFormat = liveFormat
    }

    fun refreshCatalogue() {
        c.xtream.clearCache()
        message = "Catalogue cache cleared. Lists will reload as you browse."
    }

    fun clearHistory() {
        viewModelScope.launch {
            c.library.clearAll()
            message = "Favourites and watch history cleared."
        }
    }

    fun logout(onDone: () -> Unit) {
        c.credentialsStore.clear()
        c.xtream.clearCache()
        onDone()
    }
}

@Composable
fun SettingsScreen(navigator: AppNavigator) {
    val vm = appViewModel { SettingsViewModel(it) }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = TopBarHeight + 24.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        InfoLine("Server", vm.server)
        InfoLine("Username", vm.username)
        val a = vm.account
        if (a != null) {
            InfoLine("Status", (a.status ?: "Unknown") + if (a.isTrial) " (trial)" else "")
            InfoLine("Expires", a.expiresAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Never")
            InfoLine("Connections", "${a.activeConnections ?: 0} / ${a.maxConnections ?: "?"}")
        } else if (vm.accountError != null) {
            InfoLine("Status", vm.accountError!!)
        }
        Spacer(Modifier.height(12.dp))
        Text("Playback", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = vm::toggleLiveFormat) {
                Text("Live format: " + if (vm.liveFormat == "ts") "MPEG-TS" else "HLS (m3u8)")
            }
            Button(onClick = vm::refreshCatalogue) { Text("Reload catalogue") }
            Button(onClick = vm::clearHistory) { Text("Clear favourites & history") }
            Button(
                onClick = { vm.logout { navigator.toLogin() } },
                colors = ButtonDefaults.colors(containerColor = AppColors.Accent, contentColor = AppColors.OnBackground),
            ) { Text("Sign out") }
        }
        vm.message?.let { Text(it, color = AppColors.Muted) }
        Spacer(Modifier.height(12.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = AppColors.Muted, modifier = Modifier.width(140.dp))
        Text(value)
    }
}

package com.shizington.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.shizington.tv.ui.components.TvTextField
import com.shizington.tv.ui.components.tryRequestFocus
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
    var quality by mutableStateOf(c.credentialsStore.preferredQuality)
        private set
    val showAdult get() = c.credentialsStore.showAdult
    val hasPin get() = c.credentialsStore.adultPin != null
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

    fun cycleQuality() {
        quality = when (quality) { "best" -> "hd"; "hd" -> "sd"; else -> "best" }
        c.credentialsStore.preferredQuality = quality
    }

    /** Returns true when the PIN is accepted (or newly set) and adult content is now shown. */
    fun unlockAdult(pin: String): Boolean {
        val saved = c.credentialsStore.adultPin
        if (saved == null) c.credentialsStore.adultPin = pin else if (saved != pin) return false
        c.credentialsStore.showAdult = true
        return true
    }

    fun hideAdult() {
        c.credentialsStore.showAdult = false
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
    var pinDialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = TopBarHeight + 24.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Account", style = MaterialTheme.typography.headlineSmall)
            InfoLine("Server", vm.server)
            InfoLine("Username", vm.username)
            val a = vm.account
            if (a != null) {
                InfoLine("Status", (a.status ?: "Unknown") + if (a.isTrial) " (trial)" else "")
                InfoLine(
                    "Expires",
                    a.expiresAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) + (a.daysLeft()?.let { d -> "  ($d days left)" } ?: "") } ?: "Never",
                )
                InfoLine("Connections", "${a.activeConnections ?: 0} in use of ${a.maxConnections ?: "?"} allowed")
            } else if (vm.accountError != null) {
                InfoLine("Status", vm.accountError!!)
            }
            Spacer(Modifier.height(8.dp))
            Text("Playback", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = vm::cycleQuality) {
                    Text("Channel quality: " + when (vm.quality) { "hd" -> "HD"; "sd" -> "SD (saves data)"; else -> "Best available" })
                }
                Button(onClick = vm::toggleLiveFormat) {
                    Text("Live format: " + if (vm.liveFormat == "ts") "MPEG-TS" else "HLS (m3u8)")
                }
                Button(onClick = {
                    if (vm.showAdult) {
                        vm.hideAdult()
                        navigator.toMain()
                    } else {
                        pinDialog = true
                    }
                }) {
                    Text("Adult channels: " + if (vm.showAdult) "Shown" else "Hidden")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Library", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = vm::refreshCatalogue) { Text("Reload catalogue") }
                Button(onClick = vm::clearHistory) { Text("Clear favourites & history") }
                Button(
                    onClick = { vm.logout { navigator.toLogin() } },
                    colors = ButtonDefaults.colors(containerColor = AppColors.Accent, contentColor = AppColors.OnBackground),
                ) { Text("Sign out") }
            }
            vm.message?.let { Text(it, color = AppColors.Muted) }
            Spacer(Modifier.height(8.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (pinDialog) {
            PinDialog(
                creating = !vm.hasPin,
                onSubmit = { pin ->
                    if (vm.unlockAdult(pin)) {
                        pinDialog = false
                        navigator.toMain()
                        true
                    } else false
                },
                onDismiss = { pinDialog = false },
            )
        }
    }
}

/** Asks for the 4-digit PIN that guards adult channels (or sets it the first time). */
@Composable
private fun PinDialog(creating: Boolean, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    LaunchedEffect(Unit) { field.tryRequestFocus() }
    BackHandler(onBack = onDismiss)
    fun submit() {
        when {
            pin.length != 4 || !pin.all(Char::isDigit) -> error = "Enter 4 digits"
            !onSubmit(pin) -> { error = "Wrong PIN"; pin = "" }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(440.dp)
                // Keep the D-pad inside the dialog; Back is the way out.
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .background(AppColors.Surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (creating) "Set a PIN for adult channels" else "Enter your PIN", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (creating) "You'll need this PIN to show adult channels again later." else "Adult channels are locked.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            TvTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter(Char::isDigit).take(4); error = null },
                placeholder = "4-digit PIN",
                // Down from the field should land on the confirm button, not whichever button is nearest its centre.
                modifier = Modifier.fillMaxWidth().focusRequester(field).focusProperties { down = confirm },
                password = true,
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
                onImeAction = { submit() },
            )
            error?.let { Text(it, color = AppColors.Accent) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { submit() }, modifier = Modifier.focusRequester(confirm)) { Text(if (creating) "Set PIN" else "Unlock") }
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, color = AppColors.Muted, modifier = Modifier.width(140.dp))
        Text(value)
    }
}

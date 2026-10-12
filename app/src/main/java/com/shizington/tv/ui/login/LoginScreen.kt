package com.shizington.tv.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.AppContainer
import com.shizington.tv.BuildConfig
import com.shizington.tv.data.AuthException
import com.shizington.tv.data.XtreamRepository
import com.shizington.tv.data.model.Credentials
import com.shizington.tv.ui.components.Loader
import com.shizington.tv.ui.components.TvTextField
import com.shizington.tv.ui.components.appViewModel
import com.shizington.tv.ui.components.tryRequestFocus
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException

sealed interface SplashResult {
    data object Main : SplashResult
    data class Login(val message: String?) : SplashResult
}

class SplashViewModel(private val c: AppContainer) : ViewModel() {
    private val _result = MutableStateFlow<SplashResult?>(null)
    val result = _result.asStateFlow()

    init {
        viewModelScope.launch {
            val creds = c.credentialsStore.load()
            if (creds == null) {
                _result.value = SplashResult.Login(null)
                return@launch
            }
            c.xtream.setCredentials(creds)
            _result.value = try {
                c.xtream.authenticate()
                SplashResult.Main
            } catch (e: AuthException) {
                SplashResult.Login(e.message)
            } catch (e: Exception) {
                // Offline or a flaky server: go ahead, screens offer retry.
                SplashResult.Main
            }
        }
    }
}

@Composable
fun SplashScreen(navigator: AppNavigator) {
    val vm = appViewModel { SplashViewModel(it) }
    val result by vm.result.collectAsStateWithLifecycle()
    LaunchedEffect(result) {
        when (val r = result) {
            SplashResult.Main -> navigator.toMain()
            is SplashResult.Login -> navigator.toLogin(r.message)
            null -> Unit
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(40)
            Spacer(Modifier.height(24.dp))
            Loader(Modifier.size(48.dp))
        }
    }
}

@Composable
fun Logo(sizeSp: Int) {
    Text("ALICE TV", color = AppColors.Accent, fontSize = sizeSp.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
}

class LoginViewModel(private val c: AppContainer, initialMessage: String?) : ViewModel() {
    var server by mutableStateOf(BuildConfig.DEFAULT_SERVER)
    var backups by mutableStateOf(BuildConfig.DEFAULT_BACKUPS)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf(initialMessage)
        private set

    init {
        c.credentialsStore.load()?.let {
            server = it.server
            backups = it.backupServers.joinToString(", ")
            username = it.username
            password = it.password
        }
    }

    fun submit(onSuccess: () -> Unit) {
        if (loading) return
        if (server.isBlank() || username.isBlank() || password.isBlank()) {
            error = "Please fill in server, username and password"
            return
        }
        val backupList = backups.split(',', ' ', '\n').filter { it.isNotBlank() }.map(XtreamRepository::normalizeServer)
        val creds = Credentials(XtreamRepository.normalizeServer(server), username.trim(), password, backupList)
        loading = true
        error = null
        viewModelScope.launch {
            try {
                c.xtream.setCredentials(creds)
                c.xtream.authenticate()
                if (c.credentialsStore.lastAccountKey != null && c.credentialsStore.lastAccountKey != creds.accountKey) {
                    c.library.clearAll()
                }
                c.credentialsStore.save(creds)
                onSuccess()
            } catch (e: AuthException) {
                error = e.message
            } catch (e: IOException) {
                error = "Couldn't reach the server (${e.message ?: e.javaClass.simpleName})"
            } catch (e: Exception) {
                error = "Sign in failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun LoginScreen(navigator: AppNavigator, message: String?) {
    val vm = appViewModel { LoginViewModel(it, message) }
    val focusManager = LocalFocusManager.current
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstField.tryRequestFocus() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(300.dp)) {
                Logo(36)
                Spacer(Modifier.height(12.dp))
                Text("Sign in with your Xtream Codes account.", style = MaterialTheme.typography.bodyLarge, color = AppColors.Muted)
                Spacer(Modifier.height(8.dp))
                Text("Server example: http://provider.com:8080", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
                Text("Backup servers are used automatically if the main one is down.", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
            }
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvTextField(
                    vm.server, { vm.server = it }, "Server URL",
                    modifier = Modifier.fillMaxWidth().focusRequester(firstField),
                    keyboardType = KeyboardType.Uri,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                TvTextField(
                    vm.backups, { vm.backups = it }, "Backup servers (optional, comma separated)",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                TvTextField(
                    vm.username, { vm.username = it }, "Username",
                    modifier = Modifier.fillMaxWidth(),
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                TvTextField(
                    vm.password, { vm.password = it }, "Password",
                    modifier = Modifier.fillMaxWidth(),
                    password = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    onImeAction = { vm.submit(navigator::toMain) },
                )
                vm.error?.let { Text(it, color = AppColors.Accent, style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { vm.submit(navigator::toMain) },
                        enabled = !vm.loading,
                        colors = ButtonDefaults.colors(containerColor = AppColors.Accent, contentColor = AppColors.OnBackground),
                    ) {
                        Text(if (vm.loading) "Signing in…" else "Sign in", modifier = Modifier.padding(horizontal = 24.dp))
                    }
                    if (vm.loading) Loader(Modifier.size(28.dp), size = 24.dp)
                }
            }
        }
    }
}

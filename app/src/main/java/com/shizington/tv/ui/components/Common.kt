package com.shizington.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.shizington.tv.AppContainer
import com.shizington.tv.ShizingtonApp
import com.shizington.tv.ui.theme.AppColors

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as ShizingtonApp).container

/** ViewModel scoped to the current nav destination, built from the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Scrolls so the focused child's leading edge sits [pivot] from the container start (Netflix-style). */
@Composable
fun rememberPivotSpec(pivot: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { pivot.toPx() }
    return remember(px) {
        object : BringIntoViewSpec {
            // The lazy list clamps at its ends, so the first and last items still sit naturally.
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (size >= containerSize) offset else offset - px
        }
    }
}

fun FocusRequester.tryRequestFocus(): Boolean = runCatching { requestFocus() }.isSuccess

@Composable
fun Loader(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(size), color = AppColors.Accent, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = AppColors.Muted, textAlign = TextAlign.Center)
        if (onRetry != null) Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Color.White.copy(alpha = 0.25f)) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(AppColors.Accent))
    }
}

/** Image with a text placeholder when the URL is missing or fails, common for IPTV artwork. */
@Composable
fun RemoteImage(
    url: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackBackground: Color = AppColors.SurfaceHigh,
) {
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (failed) {
            Box(Modifier.fillMaxSize().background(fallbackBackground), contentAlignment = Alignment.Center) {
                Text(
                    fallbackText,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = AppColors.OnBackground,
                )
            }
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        }
    }
}

val BottomScrim = Brush.verticalGradient(listOf(Color.Transparent, AppColors.Background))
val StartScrim = Brush.horizontalGradient(listOf(AppColors.Background, AppColors.Background.copy(alpha = 0.7f), Color.Transparent))

/** D-pad friendly text field: shows a focus ring and opens the IME on centre/enter. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(AppColors.Accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    keyboard?.show(); true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) AppColors.SurfaceHigh else AppColors.Surface)
                    .border(2.dp, if (focused) AppColors.Focus else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = AppColors.Muted, fontSize = 16.sp)
                inner()
            }
        },
    )
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val clockFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
fun formatClock(ms: Long): String = synchronized(clockFormat) { clockFormat.format(java.util.Date(ms)) }

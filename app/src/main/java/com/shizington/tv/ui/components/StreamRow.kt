package com.shizington.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.shizington.tv.data.model.ContentType
import com.shizington.tv.data.model.Stream
import com.shizington.tv.ui.theme.AppColors

val RowStartPadding = 48.dp

sealed interface RowContent {
    data object Loading : RowContent
    data class Ready(val items: List<Stream>) : RowContent
    data class Failed(val message: String) : RowContent
}

/** Section title + horizontally scrolling content, with focus restoration and a left pivot. */
@Composable
fun RowSection(
    title: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.OnBackground,
            modifier = Modifier.padding(start = RowStartPadding, bottom = 8.dp),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberPivotSpec(RowStartPadding)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(horizontal = RowStartPadding, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/**
 * A catalogue row that loads itself the first time it is composed (lazy loading per category).
 * [restoreKey] is the key of an item that should grab focus, e.g. after returning from a detail page.
 */
@Composable
fun StreamRow(
    rowKey: String,
    title: String,
    content: RowContent?,
    onLoad: () -> Unit,
    onFocused: (Stream) -> Unit,
    onClick: (Stream) -> Unit,
    restoreKey: String?,
    onRestored: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(rowKey) { if (content == null || content is RowContent.Failed) onLoad() }
    when (content) {
        null, RowContent.Loading -> PlaceholderRow(title, modifier)
        is RowContent.Failed -> PlaceholderRow(title, modifier, content.message)
        is RowContent.Ready -> {
            if (content.items.isEmpty()) return
            RowSection(title, modifier) {
                itemsIndexed(content.items, key = { _, s -> s.key }) { _, stream ->
                    val itemKey = "$rowKey/${stream.key}"
                    val requester = remember { FocusRequester() }
                    if (restoreKey == itemKey) {
                        LaunchedEffect(Unit) {
                            requester.tryRequestFocus()
                            onRestored()
                        }
                    }
                    val m = Modifier.focusRequester(requester)
                    if (stream.type == ContentType.LIVE) {
                        ChannelCard(stream, onClick = { onClick(stream) }, modifier = m, onFocused = { onFocused(stream) })
                    } else {
                        PosterCard(stream, onClick = { onClick(stream) }, modifier = m, onFocused = { onFocused(stream) })
                    }
                }
            }
        }
    }
}

@Composable
fun PlaceholderRow(title: String, modifier: Modifier = Modifier, message: String? = null) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Muted, modifier = Modifier.padding(start = RowStartPadding, bottom = 8.dp))
        Box(Modifier.fillMaxWidth().height(170.dp).padding(start = RowStartPadding)) {
            if (message != null) Text(message, color = AppColors.Muted, style = MaterialTheme.typography.bodyMedium)
            else SkeletonScope { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(9) { SkeletonBlock(108.dp, 162.dp) } } }
        }
    }
}

package com.shizington.tv.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.shizington.tv.ui.browse.BrowseMode
import com.shizington.tv.ui.browse.BrowseScreen
import com.shizington.tv.ui.components.appContainer
import com.shizington.tv.ui.components.tryRequestFocus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shizington.tv.ui.favourites.FavouritesScreen
import com.shizington.tv.ui.live.LiveScreen
import com.shizington.tv.ui.navigation.AppNavigator
import com.shizington.tv.ui.search.SearchScreen
import com.shizington.tv.ui.settings.SettingsScreen
import com.shizington.tv.ui.theme.AppColors
import kotlinx.coroutines.delay

private enum class MainTab(val title: String) {
    HOME("Home"), LIVE("Live TV"), MOVIES("Movies"), SERIES("Series"), SEARCH("Search"), FAVOURITES("Favourites"), SETTINGS("Settings")
}

val TopBarHeight = 56.dp

@Composable
fun MainScreen(navigator: AppNavigator) {
    val tabs = MainTab.entries
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabRequesters = remember { List(tabs.size) { FocusRequester() } }
    var anythingFocused by remember { mutableStateOf(false) }
    val account by appContainer().xtream.account.collectAsStateWithLifecycle()

    BackHandler(enabled = selected != 0) {
        selected = 0
        tabRequesters[0].tryRequestFocus()
    }

    // If the content didn't restore focus itself (first launch, or nothing to restore), land on the tabs.
    LaunchedEffect(Unit) {
        delay(350)
        if (!anythingFocused) tabRequesters[selected].tryRequestFocus()
    }

    Box(Modifier.fillMaxSize().onFocusChanged { anythingFocused = it.hasFocus }) {
        Box(Modifier.fillMaxSize()) {
            when (tabs[selected]) {
                MainTab.HOME -> BrowseScreen(BrowseMode.HOME, navigator)
                MainTab.MOVIES -> BrowseScreen(BrowseMode.MOVIES, navigator)
                MainTab.SERIES -> BrowseScreen(BrowseMode.SERIES, navigator)
                MainTab.LIVE -> LiveScreen(navigator)
                MainTab.SEARCH -> SearchScreen(navigator)
                MainTab.FAVOURITES -> FavouritesScreen(navigator)
                MainTab.SETTINGS -> SettingsScreen(navigator)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(TopBarHeight)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                .padding(start = 48.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ALICE TV", color = AppColors.Accent, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.width(32.dp))
            TabRow(
                selectedTabIndex = selected,
                // Entering the tab row from below always lands on the selected tab, never a neighbour.
                modifier = Modifier
                    .focusProperties { enter = { tabRequesters[selected] } }
                    .focusGroup(),
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selected,
                        onFocus = { selected = index },
                        onClick = { selected = index },
                        modifier = Modifier.focusRequester(tabRequesters[index]),
                    ) {
                        Text(tab.title, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Warn a week ahead so the subscription doesn't lapse mid-film.
            val daysLeft = account?.daysLeft()
            if (daysLeft != null && daysLeft <= 7) {
                Text(
                    when (daysLeft) {
                        0L -> "Expires today"
                        1L -> "Expires tomorrow"
                        else -> "Expires in $daysLeft days"
                    },
                    color = AppColors.Rating,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

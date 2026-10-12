package com.shizington.tv

import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ComposeView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.shizington.tv.ui.navigation.AppNavHost
import com.shizington.tv.ui.theme.AppColors
import com.shizington.tv.ui.theme.ShizingtonTheme

/** The activity-level video view that sits underneath the Compose UI (see MainActivity). */
val LocalVideoView = staticCompositionLocalOf<PlayerView?> { null }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The video view lives in the activity's own layout, beneath the Compose UI, rather than inside
        // Compose. Its SurfaceView then cuts its transparent hole straight into the window; hosted inside
        // Compose, Fire OS 7 left that hole opaque and the picture stayed black. Keeping the hardware video
        // layer (instead of a TextureView) also keeps Fire TV's video scaler and sharpening.
        val videoView = (layoutInflater.inflate(R.layout.player_view, null) as PlayerView).apply {
            visibility = View.GONE
        }
        val ui = ComposeView(this).apply {
            setContent {
                CompositionLocalProvider(LocalVideoView provides videoView) {
                    ShizingtonTheme {
                        val playerVisible by (application as ShizingtonApp).container.playerVisible
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RectangleShape,
                            // While the player is up the video shows through from underneath, so nothing here may paint over it.
                            colors = SurfaceDefaults.colors(
                                containerColor = if (playerVisible) Color.Transparent else AppColors.Background,
                                contentColor = AppColors.OnBackground,
                            ),
                        ) {
                            AppNavHost()
                        }
                    }
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(videoView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(ui, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            },
        )
    }
}

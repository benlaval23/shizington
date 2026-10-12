package com.shizington.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import com.shizington.tv.ui.navigation.AppNavHost
import com.shizington.tv.ui.theme.AppColors
import com.shizington.tv.ui.theme.ShizingtonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShizingtonTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(containerColor = AppColors.Background, contentColor = AppColors.OnBackground),
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

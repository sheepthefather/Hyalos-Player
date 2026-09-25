package com.hyalos.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hyalos.player.ui.AppNavigation
import com.hyalos.player.ui.theme.HyalosTheme

/** The only activity. Every screen is a Navigation 3 entry inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HyalosApp).container
        setContent {
            HyalosTheme {
                AppNavigation(container)
            }
        }
    }
}

package dev.antonix.deep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import dev.antonix.deep.ui.DeepScreen
import dev.antonix.deep.ui.DeepViewModel
import dev.antonix.deep.ui.theme.DeepTheme

class MainActivity : ComponentActivity() {
    private val vm: DeepViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            DeepTheme {
                DeepScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) vm.release()
    }
}

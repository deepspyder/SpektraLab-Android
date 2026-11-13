package com.spektralab.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.spektralab.android.app.LaunchSplash
import com.spektralab.android.app.TheiaRoot
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Preserve profiled original previews on capable screens; SDR devices keep their normal path.
        if (resources.configuration.isScreenWideColorGamut) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        }
        splash.setOnExitAnimationListener { splashView ->
            splashView.view.animate()
                .alpha(0f)
                .scaleX(1.035f)
                .scaleY(1.035f)
                .setDuration(190L)
                .withEndAction { splashView.remove() }
                .start()
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.rgb(21, 21, 21)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        vm.restoreUri(savedInstanceState?.getString("currentUri"))
        setContent {
            TheiaTheme {
                var minimumSplashElapsed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(1200L)
                    minimumSplashElapsed = true
                }
                if (vm.errorMessage == null &&
                    (!minimumSplashElapsed || (vm.engine == null && vm.busy))) {
                    LaunchSplash()
                } else {
                    TheiaRoot(vm)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentUri", vm.currentUri?.toString())
    }
}

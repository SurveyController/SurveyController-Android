package com.surveycontroller.android.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.surveycontroller.android.core.engine.RunStatus
import com.surveycontroller.android.data.SettingsStore
import com.surveycontroller.android.ui.theme.SurveyControllerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val settings by viewModel.settingsStore.settings.collectAsState(initial = SettingsStore.Settings())
            val progress by viewModel.runProgress.collectAsState()

            // 启动时检查更新
            LaunchedEffect(Unit) {
                if (settings.autoCheckUpdate) viewModel.checkUpdate()
            }

            // 执行期间保持屏幕常亮
            val view = LocalView.current
            val keepOn = settings.preventSleepDuringRun &&
                (progress.status == RunStatus.RUNNING || progress.status == RunStatus.PAUSED)
            DisposableEffect(keepOn) {
                val window = (view.context as? ComponentActivity)?.window
                if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            val darkTheme = when (settings.themeMode) {
                SettingsStore.ThemeMode.LIGHT -> false
                SettingsStore.ThemeMode.DARK -> true
                SettingsStore.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            SurveyControllerTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                AppNavigation(viewModel = viewModel, navigationLabelVisible = settings.navigationLabelVisible)
            }
        }
    }
}

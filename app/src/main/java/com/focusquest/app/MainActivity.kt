package com.focusquest.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.focusquest.app.ui.GroupReportScreen
import com.focusquest.app.ui.JoinClassScreen
import com.focusquest.app.ui.MainScreen
import com.focusquest.app.ui.ReportsScreen
import com.focusquest.app.ui.TrainerScreen
import com.focusquest.app.ui.ProfileEditorScreen
import com.focusquest.app.ui.ResultScreen
import com.focusquest.app.ui.SessionScreen
import com.focusquest.app.ui.SettingsScreen
import com.focusquest.app.ui.theme.FocusQuestTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so always use light system-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            FocusQuestTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    // Session screen installs its own back handling (pause), so it isn't covered here.
    BackHandler(enabled = vm.canGoBack && vm.current !is Screen.Session) { vm.back() }

    when (val screen = vm.current) {
        Screen.Onboarding -> ProfileEditorScreen(firstRun = true, onBack = null) { name, age, color ->
            vm.store.addProfile(name, age, color)
            vm.reset(Screen.Main)
        }
        Screen.AddProfile -> ProfileEditorScreen(firstRun = false, onBack = { vm.back() }) { name, age, color ->
            val p = vm.store.addProfile(name, age, color)
            vm.store.setActiveProfile(p.id)
            vm.back()
        }
        Screen.Main -> MainScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        is Screen.Session -> SessionScreen(vm, screen.game, screen.durationSec, screen.groupId)
        is Screen.Result -> ResultScreen(vm, screen.record, screen.achievement, screen.plannedSec)
        Screen.JoinClass -> JoinClassScreen(vm)
        Screen.Trainer -> TrainerScreen(vm)
        is Screen.Reports -> ReportsScreen(vm, screen.trainer)
        is Screen.GroupReport -> GroupReportScreen(vm, screen.groupId)
    }

    // The trainer started a class game but this phone has no headset connected yet.
    vm.pendingClassStart?.let { start ->
        AlertDialog(
            onDismissRequest = { vm.pendingClassStart = null },
            title = { Text("Your trainer started ${start.game.title}") },
            text = { Text("Connect your headset to join in, or play with the simulator.") },
            confirmButton = {
                TextButton(onClick = { vm.pendingClassStart = null; vm.reset(Screen.Main); vm.tab = Tab.Device }) { Text("Connect headset") }
            },
            dismissButton = {
                TextButton(onClick = { vm.hub.startSimulator(); vm.startClassSession(start) }) { Text("Use simulator") }
            },
        )
    }
}

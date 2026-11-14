package com.focusquest.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focusquest.app.classroom.ClassEvent
import com.focusquest.app.data.GameId
import kotlinx.coroutines.launch
import com.focusquest.app.data.SessionRecord

sealed interface Screen {
    data object Onboarding : Screen
    data object Main : Screen
    /** [groupId] is set when a trainer started this session for the whole class. */
    data class Session(val game: GameId, val durationSec: Int, val groupId: String? = null) : Screen
    /** [plannedSec] is the length the player picked (0 when opened from history). */
    data class Result(val record: SessionRecord, val achievement: String, val plannedSec: Int = 0) : Screen
    data object Settings : Screen
    data object AddProfile : Screen
    data object JoinClass : Screen
    data object Trainer : Screen
    data class Reports(val trainer: Boolean) : Screen
    data class GroupReport(val groupId: String) : Screen
}

enum class Tab { Train, Progress, Device }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val fq = app as FocusQuestApp
    val hub get() = fq.hub
    val store get() = fq.store
    val trainer get() = fq.trainer
    val studentLink get() = fq.studentLink
    val liveSession get() = fq.liveSession

    private val stack = mutableStateListOf<Screen>(
        if (fq.store.profiles.value.isEmpty()) Screen.Onboarding else Screen.Main,
    )
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var tab by mutableStateOf(Tab.Train)

    /** A trainer asked to start a game but no headset is connected yet. */
    var pendingClassStart by mutableStateOf<ClassEvent.Start?>(null)

    /** Group id the trainer asked to stop; the session screen watches this. */
    var classStopRequest by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            fq.studentLink.events.collect { e ->
                when (e) {
                    is ClassEvent.Start -> {
                        if (current is Screen.Session) return@collect // don't interrupt a game in progress
                        if (fq.hub.state.value.connected) startClassSession(e) else pendingClassStart = e
                    }
                    is ClassEvent.Stop -> classStopRequest = e.groupId
                    is ClassEvent.Cheer -> Toast.makeText(fq, "🎉 Trainer: ${e.text}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun startClassSession(e: ClassEvent.Start) {
        pendingClassStart = null
        classStopRequest = null
        reset(Screen.Main, Screen.Session(e.game, e.durationSec, e.groupId))
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Replace the whole stack, e.g. after onboarding or when a session ends. */
    fun reset(vararg screens: Screen) {
        stack.clear()
        stack.addAll(screens)
    }

    fun replaceTop(screen: Screen) {
        stack[stack.lastIndex] = screen
    }
}

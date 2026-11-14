package com.focusquest.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focusquest.app.AppViewModel
import com.focusquest.app.Screen
import com.focusquest.app.classroom.LiveSession
import com.focusquest.app.data.GameId
import com.focusquest.app.data.Metric
import com.focusquest.app.eeg.SimMode
import com.focusquest.app.eeg.SourceKind
import com.focusquest.app.games.createGame
import com.focusquest.app.session.SessionEngine
import com.focusquest.app.ui.theme.FqColors

private const val COUNTDOWN_SEC = 3f
private const val MIN_SAVE_SEC = 30

@Composable
fun SessionScreen(vm: AppViewModel, game: GameId, durationSec: Int, groupId: String? = null) {
    val hub = vm.hub
    val eeg by hub.state.collectAsStateWithLifecycle()
    val simMode by hub.simMode.collectAsStateWithLifecycle()
    val settings = remember { vm.store.settings.value }
    val profileId = settings.activeProfileId ?: vm.store.profiles.value.firstOrNull()?.id ?: ""

    val engine = remember { SessionEngine(durationSec, settings.threshold, settings.adaptiveThreshold, game.metric) }
    val focusGame = remember { createGame(game) }
    val startedAt = remember { System.currentTimeMillis() }
    var time by remember { mutableFloatStateOf(0f) }
    var countdown by remember { mutableFloatStateOf(COUNTDOWN_SEC) }
    var frame by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    val latestEeg by rememberUpdatedState(eeg)
    val haptics = LocalHapticFeedback.current

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            hub.simulatorBoost = false
            vm.liveSession.value = null
        }
    }

    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return
        finished = true
        val sourceName = if (latestEeg.source == SourceKind.Simulator) "Simulator" else (latestEeg.deviceName ?: "Headset")
        val record = engine.toRecord(profileId, game, startedAt, sourceName)
        vm.store.addSession(record)
        vm.studentLink.sendResult(record, groupId)
        vm.replaceTop(Screen.Result(record, focusGame.achievement, durationSec))
    }

    fun quit() {
        // Class sessions are always reported so the trainer's report has every student.
        if (engine.elapsed >= MIN_SAVE_SEC || groupId != null) finish() else vm.back()
    }

    // Trainer ended the group session for everyone.
    val stopRequest = vm.classStopRequest
    LaunchedEffect(stopRequest) {
        if (groupId != null && stopRequest == groupId) {
            vm.classStopRequest = null
            finish()
        }
    }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (!engine.finished) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
            last = now
            time += dt
            val s = latestEeg
            when {
                paused -> Unit
                countdown > 0f -> countdown -= dt
                s.signalOk -> {
                    val step = engine.step(dt, s.attention, s.meditation)
                    focusGame.update(dt, step.focusLevel, step.focused)
                    if (step.streakMilestone && settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            frame++
            if (frame % 20 == 0L) {
                vm.liveSession.value = LiveSession(
                    game = game,
                    elapsedSec = engine.elapsed.toInt(),
                    durationSec = durationSec,
                    focusPct = engine.focusPercent,
                    points = engine.points.toInt(),
                    streakSec = engine.currentStreak.toInt(),
                    threshold = engine.threshold,
                    focused = engine.focused,
                    progress = focusGame.progressLabel,
                    paused = paused || countdown > 0f || !s.signalOk,
                    groupId = groupId,
                )
            }
        }
        finish()
    }

    BackHandler { paused = true }
    // Leaving the app (call, home button) pauses the session instead of letting the timer run.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { paused = true }

    val touchSim = eeg.source == SourceKind.Simulator && simMode == SimMode.Touch
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (touchSim) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        hub.simulatorBoost = true
                        waitForUpOrCancellation()
                        hub.simulatorBoost = false
                    }
                } else Modifier,
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame // redraw every frame
            focusGame.draw(this, time)
        }

        Hud(
            frame = frame,
            engine = engine,
            level = if (game.metric == Metric.CALM) eeg.meditation else eeg.attention,
            metric = game.metric,
            progress = focusGame.progressLabel,
            touchSim = touchSim,
            onPause = { paused = true },
        )

        val signalLost = !eeg.signalOk && !paused && countdown <= 0f
        AnimatedVisibility(signalLost, enter = fadeIn(), exit = fadeOut()) {
            Overlay {
                Text("Signal paused", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (!eeg.connected) "The headset disconnected. Reconnect it to continue."
                    else "Adjust the headband so the sensor touches your forehead. The game resumes automatically.",
                    color = FqColors.TextDim,
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = { quit() }) { Text("End session") }
            }
        }

        if (countdown > 0f && !paused) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Text(
                    "${kotlin.math.ceil(countdown).toInt()}",
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
            }
        }

        if (paused) {
            Overlay {
                Text("Paused", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (engine.elapsed >= MIN_SAVE_SEC || groupId != null) "Ending now saves this session."
                    else "Sessions shorter than $MIN_SAVE_SEC seconds aren't saved.",
                    color = FqColors.TextDim,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { quit() }) { Text("End session") }
                    Button(onClick = { paused = false }) { Text("Resume") }
                }
            }
        }
    }
}

@Composable
private fun Hud(
    frame: Long,
    engine: SessionEngine,
    level: Int,
    metric: Metric,
    progress: String,
    touchSim: Boolean,
    onPause: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val tick = frame // recompose with the game loop
    val remaining = engine.remainingSec
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onPause,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.35f)),
            ) { Icon(Icons.Filled.Pause, "Pause", tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Pill("%d:%02d".format(remaining / 60, remaining % 60))
            Spacer(Modifier.weight(1f))
            Pill("⭐ %,d".format(engine.points.toInt()))
        }
        Spacer(Modifier.height(12.dp))
        val calm = metric == Metric.CALM
        FocusBar(level, engine.threshold, engine.focused, if (calm) FqColors.Calm else FqColors.Focus)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(progress, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text("${metric.label} goal ${engine.threshold}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val msg = when {
                calm && engine.focused && engine.currentStreak >= 5f -> "So calm… ${engine.currentStreak.toInt()}s 🌙"
                calm && engine.focused -> "Nice and calm, keep breathing slowly"
                engine.focused && engine.currentStreak >= 5f -> "Focused! ${engine.currentStreak.toInt()}s streak 🔥"
                engine.focused -> "Focused, keep it up!"
                touchSim -> if (calm) "Hold the screen to simulate calm" else "Hold the screen to simulate focus"
                calm -> "Relax your shoulders and breathe out slowly…"
                else -> "Bring your attention back…"
            }
            Text(
                msg,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun FocusBar(level: Int, threshold: Int, focused: Boolean, color: Color) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.4f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.coerceIn(0, 100) / 100f)
                .clip(RoundedCornerShape(50))
                .background(if (focused) color else FqColors.Warm),
        )
        Box(
            Modifier
                .offset(x = maxWidth * (threshold / 100f) - 1.5.dp)
                .width(3.dp)
                .fillMaxHeight()
                .background(Color.White),
        )
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(FqColors.Surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

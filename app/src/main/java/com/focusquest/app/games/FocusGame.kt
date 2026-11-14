package com.focusquest.app.games

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.focusquest.app.data.GameId

/**
 * A neurofeedback mini-game. The session loop calls [update] every frame with the
 * player's current focus, and the screen calls [draw] to render it.
 */
interface FocusGame {
    /** @param focus smoothed attention mapped to 0..1 relative to the threshold. */
    fun update(dt: Float, focus: Float, focused: Boolean)

    fun draw(scope: DrawScope, time: Float)

    /** Short progress readout shown in the HUD, e.g. "1,240 m". */
    val progressLabel: String

    /** Summary line for the results screen. */
    val achievement: String
}

fun createGame(id: GameId): FocusGame = when (id) {
    GameId.ROCKET -> RocketGame()
    GameId.GARDEN -> GardenGame()
    GameId.DRIVE -> DriveGame()
    GameId.MOSAIC -> MosaicGame()
    GameId.BRIDGE -> BridgeGame()
    GameId.ARCHERY -> ArcheryGame()
    GameId.BALLOON -> BalloonGame()
    GameId.LANTERN -> LanternGame()
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

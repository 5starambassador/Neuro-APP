package com.focusquest.app.classroom

import com.focusquest.app.data.GameId
import com.focusquest.app.data.Profile
import com.focusquest.app.data.SessionRecord
import com.focusquest.app.data.toJson
import com.focusquest.app.eeg.EegState
import org.json.JSONObject
import java.io.InputStream

/**
 * Classroom protocol: newline-delimited JSON over a TCP socket on the local Wi‑Fi.
 *
 * Student → trainer:  hello (first line, carries the join code) · state (every second) · result
 * Trainer → student:  welcome | error · start · stop · cheer · ack (for a result)
 *
 * Everything stays on the local network; nothing is sent to the internet.
 */
object Protocol {
    const val SERVICE_TYPE = "_focusquest._tcp."
    const val VERSION = 1
    const val MAX_LINE = 64 * 1024

    fun hello(code: String, profile: Profile, device: String): JSONObject = JSONObject()
        .put("t", "hello").put("v", VERSION).put("code", code)
        .put("profile", profile.toJson()).put("device", device)

    fun welcome(className: String) = JSONObject().put("t", "welcome").put("className", className)
    fun error(message: String) = JSONObject().put("t", "error").put("msg", message)
    fun start(groupId: String, game: GameId, durationSec: Int) = JSONObject()
        .put("t", "start").put("group", groupId).put("game", game.name).put("duration", durationSec)
    fun stop(groupId: String) = JSONObject().put("t", "stop").put("group", groupId)
    fun cheer(text: String) = JSONObject().put("t", "cheer").put("text", text)
    fun ack(recordId: String) = JSONObject().put("t", "ack").put("id", recordId)

    fun result(record: SessionRecord, groupId: String?) = JSONObject()
        .put("t", "result").put("group", groupId ?: JSONObject.NULL).put("record", record.toJson())

    fun state(eeg: EegState, live: LiveSession?): JSONObject {
        val o = JSONObject()
            .put("t", "state")
            .put("att", eeg.attention).put("med", eeg.meditation)
            .put("sig", eeg.signalQuality).put("ok", eeg.signalOk)
            .put("src", eeg.source.name).put("link", eeg.link.name)
        if (live != null) {
            o.put("session", JSONObject()
                .put("game", live.game.name).put("elapsed", live.elapsedSec).put("duration", live.durationSec)
                .put("focusPct", live.focusPct).put("points", live.points).put("streak", live.streakSec)
                .put("threshold", live.threshold).put("focused", live.focused).put("progress", live.progress)
                .put("paused", live.paused).put("group", live.groupId ?: JSONObject.NULL))
        }
        return o
    }

    /**
     * Reads one '\n'-terminated UTF‑8 line, refusing anything longer than [MAX_LINE] so a
     * misbehaving client can't exhaust memory. Returns null at end of stream.
     */
    fun readLine(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name())
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE) throw java.io.IOException("Line too long")
        }
    }
}

/** What a student's game screen is doing right now; published for the trainer. */
data class LiveSession(
    val game: GameId,
    val elapsedSec: Int,
    val durationSec: Int,
    val focusPct: Int,
    val points: Int,
    val streakSec: Int,
    val threshold: Int,
    val focused: Boolean,
    val progress: String,
    val paused: Boolean,
    val groupId: String?,
)

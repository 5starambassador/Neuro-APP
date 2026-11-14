package com.focusquest.app.data

import org.json.JSONArray
import org.json.JSONObject

/** JSON (de)serialisation shared by local storage and the classroom Wi‑Fi protocol. */

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

fun Profile.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("age", age).put("color", colorIndex)

fun profileFromJson(o: JSONObject) = Profile(
    id = o.getString("id"),
    name = o.getString("name"),
    age = o.optInt("age", 10),
    colorIndex = o.optInt("color", 0),
)

fun SessionRecord.toJson(): JSONObject = JSONObject()
    .put("id", id).put("profileId", profileId).put("game", game.name)
    .put("startedAt", startedAt).put("durationSec", durationSec)
    .put("focusedSec", focusedSec).put("avgAttention", avgAttention)
    .put("avgMeditation", avgMeditation).put("longestStreakSec", longestStreakSec)
    .put("points", points).put("startThreshold", startThreshold)
    .put("endThreshold", endThreshold).put("source", source)
    .put("timeline", JSONArray(attentionTimeline))

fun sessionFromJson(o: JSONObject): SessionRecord? = try {
    val tl = o.optJSONArray("timeline")
    SessionRecord(
        id = o.getString("id"),
        profileId = o.getString("profileId"),
        game = GameId.valueOf(o.getString("game")),
        startedAt = o.getLong("startedAt"),
        durationSec = o.getInt("durationSec"),
        focusedSec = o.getInt("focusedSec"),
        avgAttention = o.optInt("avgAttention"),
        avgMeditation = o.optInt("avgMeditation"),
        longestStreakSec = o.optInt("longestStreakSec"),
        points = o.optInt("points"),
        startThreshold = o.optInt("startThreshold", 50),
        endThreshold = o.optInt("endThreshold", 50),
        source = o.optString("source", "Headset"),
        attentionTimeline = if (tl == null) emptyList() else (0 until tl.length()).map { tl.getInt(it) },
    )
} catch (_: Exception) {
    null
}

fun GroupSession.toJson(): JSONObject = JSONObject()
    .put("id", id).put("className", className).put("game", game.name)
    .put("plannedSec", plannedSec).put("startedAt", startedAt).put("endedAt", endedAt)
    .put("studentIds", JSONArray(studentIds)).put("resultIds", JSONArray(resultIds))

fun groupSessionFromJson(o: JSONObject): GroupSession? = try {
    fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    GroupSession(
        id = o.getString("id"),
        className = o.optString("className"),
        game = GameId.valueOf(o.getString("game")),
        plannedSec = o.getInt("plannedSec"),
        startedAt = o.getLong("startedAt"),
        endedAt = o.optLong("endedAt"),
        studentIds = strings(o.optJSONArray("studentIds")),
        resultIds = strings(o.optJSONArray("resultIds")),
    )
} catch (_: Exception) {
    null
}

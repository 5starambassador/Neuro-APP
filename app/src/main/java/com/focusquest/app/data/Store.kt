package com.focusquest.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local, on-device storage (a single JSON file):
 * - players on this device and their sessions;
 * - when used as a trainer device, the class roster, sessions received from students
 *   over Wi‑Fi, and group sessions.
 */
class Store(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "focusquest.json"))
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _classStudents = MutableStateFlow<List<Profile>>(emptyList())
    val classStudents: StateFlow<List<Profile>> = _classStudents.asStateFlow()

    private val _classSessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val classSessions: StateFlow<List<SessionRecord>> = _classSessions.asStateFlow()

    private val _groupSessions = MutableStateFlow<List<GroupSession>>(emptyList())
    val groupSessions: StateFlow<List<GroupSession>> = _groupSessions.asStateFlow()

    init {
        load()
    }

    // ---- players on this device ------------------------------------------------------

    fun addProfile(name: String, age: Int, colorIndex: Int): Profile {
        val p = Profile(UUID.randomUUID().toString(), name.trim(), age, colorIndex)
        _profiles.update { it + p }
        if (_settings.value.activeProfileId == null) _settings.update { it.copy(activeProfileId = p.id) }
        persist()
        return p
    }

    fun updateProfile(profile: Profile) {
        _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
        persist()
    }

    fun deleteProfile(id: String) {
        _profiles.update { list -> list.filterNot { it.id == id } }
        _sessions.update { list -> list.filterNot { it.profileId == id } }
        _settings.update { s ->
            if (s.activeProfileId == id) s.copy(activeProfileId = _profiles.value.firstOrNull()?.id) else s
        }
        persist()
    }

    fun setActiveProfile(id: String) {
        _settings.update { it.copy(activeProfileId = id) }
        persist()
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        _settings.update(transform)
        persist()
    }

    fun addSession(record: SessionRecord) {
        _sessions.update { listOf(record) + it }
        persist()
    }

    fun deleteSession(id: String) {
        _sessions.update { list -> list.filterNot { it.id == id } }
        _classSessions.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    // ---- trainer: class data ---------------------------------------------------------

    fun upsertClassStudent(student: Profile) {
        _classStudents.update { list ->
            if (list.any { it.id == student.id }) list.map { if (it.id == student.id) student else it } else list + student
        }
        persist()
    }

    fun removeClassStudent(id: String) {
        _classStudents.update { list -> list.filterNot { it.id == id } }
        _classSessions.update { list -> list.filterNot { it.profileId == id } }
        persist()
    }

    /** Adds a session reported by a student device; ignores duplicates (e.g. after a reconnect). */
    fun addClassSession(record: SessionRecord) {
        if (_classSessions.value.any { it.id == record.id }) return
        _classSessions.update { listOf(record) + it }
        persist()
    }

    fun addGroupSession(group: GroupSession) {
        _groupSessions.update { listOf(group) + it.filterNot { g -> g.id == group.id } }
        persist()
    }

    // ---- persistence -----------------------------------------------------------------

    private fun load() {
        val text = try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        try {
            val root = JSONObject(text)
            _profiles.value = root.optJSONArray("profiles").objects().map(::profileFromJson)
            _sessions.value = root.optJSONArray("sessions").objects().mapNotNull(::sessionFromJson)
            _settings.value = root.optJSONObject("settings")?.let(::settingsFrom) ?: Settings()
            _classStudents.value = root.optJSONArray("classStudents").objects().map(::profileFromJson)
            _classSessions.value = root.optJSONArray("classSessions").objects().mapNotNull(::sessionFromJson)
            _groupSessions.value = root.optJSONArray("groupSessions").objects().mapNotNull(::groupSessionFromJson)
        } catch (_: Exception) {
            // Corrupt file: start fresh rather than crash.
        }
    }

    private fun persist() {
        val snapshot = JSONObject()
            .put("version", 2)
            .put("profiles", JSONArray(_profiles.value.map { it.toJson() }))
            .put("sessions", JSONArray(_sessions.value.map { it.toJson() }))
            .put("settings", settingsJson(_settings.value))
            .put("classStudents", JSONArray(_classStudents.value.map { it.toJson() }))
            .put("classSessions", JSONArray(_classSessions.value.map { it.toJson() }))
            .put("groupSessions", JSONArray(_groupSessions.value.map { it.toJson() }))
            .toString()
        io.launch {
            synchronized(lock) {
                val out = file.startWrite()
                try {
                    out.write(snapshot.toByteArray(Charsets.UTF_8))
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                }
            }
        }
    }

    private fun settingsJson(s: Settings) = JSONObject()
        .put("defaultDurationSec", s.defaultDurationSec)
        .put("adaptiveThreshold", s.adaptiveThreshold)
        .put("threshold", s.threshold)
        .put("haptics", s.haptics)
        .put("activeProfileId", s.activeProfileId ?: JSONObject.NULL)
        .put("className", s.className)
        .put("onlineServer", s.onlineServer)
        .put("onlineToken", s.onlineToken)
        .put("onlineClassName", s.onlineClassName)

    private fun settingsFrom(o: JSONObject) = Settings(
        onlineServer = o.optString("onlineServer", ""),
        onlineToken = o.optString("onlineToken", ""),
        onlineClassName = o.optString("onlineClassName", ""),
        defaultDurationSec = o.optInt("defaultDurationSec", 300),
        adaptiveThreshold = o.optBoolean("adaptiveThreshold", true),
        threshold = o.optInt("threshold", 50),
        haptics = o.optBoolean("haptics", true),
        activeProfileId = if (o.isNull("activeProfileId")) null else o.optString("activeProfileId"),
        className = o.optString("className", ""),
    )
}

package com.focusquest.app

import android.app.Application
import com.focusquest.app.classroom.LiveSession
import com.focusquest.app.classroom.StudentLink
import com.focusquest.app.classroom.TrainerServer
import com.focusquest.app.classroom.WebDashboardServer
import com.focusquest.app.data.Store
import com.focusquest.app.eeg.EegHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FocusQuestApp : Application() {
    lateinit var hub: EegHub
        private set
    lateinit var store: Store
        private set
    lateinit var trainer: TrainerServer
        private set
    lateinit var studentLink: StudentLink
        private set
    lateinit var webDashboard: WebDashboardServer
        private set

    /** The game session currently on screen, published for the trainer's live view. */
    val liveSession = MutableStateFlow<LiveSession?>(null)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        hub = EegHub(this)
        store = Store(this)
        trainer = TrainerServer(this, store)
        studentLink = StudentLink(this, hub, store, liveSession)
        webDashboard = WebDashboardServer(this, store, trainer)

        // The browser dashboard runs exactly while a class is running.
        appScope.launch {
            trainer.state.map { it.running }.distinctUntilChanged().collect { running ->
                if (running) webDashboard.start() else webDashboard.stop()
            }
        }
    }
}

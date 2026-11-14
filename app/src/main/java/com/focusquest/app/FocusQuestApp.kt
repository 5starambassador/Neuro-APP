package com.focusquest.app

import android.app.Application
import com.focusquest.app.classroom.LiveSession
import com.focusquest.app.classroom.StudentLink
import com.focusquest.app.classroom.TrainerServer
import com.focusquest.app.data.Store
import com.focusquest.app.eeg.EegHub
import kotlinx.coroutines.flow.MutableStateFlow

class FocusQuestApp : Application() {
    lateinit var hub: EegHub
        private set
    lateinit var store: Store
        private set
    lateinit var trainer: TrainerServer
        private set
    lateinit var studentLink: StudentLink
        private set

    /** The game session currently on screen, published for the trainer's live view. */
    val liveSession = MutableStateFlow<LiveSession?>(null)

    override fun onCreate() {
        super.onCreate()
        hub = EegHub(this)
        store = Store(this)
        trainer = TrainerServer(this, store)
        studentLink = StudentLink(this, hub, store, liveSession)
    }
}

package com.prudnikweb.hypernova.data

import android.app.Application

/** [Core] on this phone: started once per process, with the application behind it. */
object AndroidCore {
    lateinit var app: Application
        private set

    val notifier: AndroidNotifier get() = Core.notifier as AndroidNotifier

    fun init(a: Application) {
        if (::app.isInitialized) return
        app = a
        Core.init(AndroidPlatform(a))
    }
}

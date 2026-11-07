package com.aiapp.builder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared Build Mode state, so the screen and the background service see the same progress.
 * Lives as long as the app process; the foreground service keeps the process alive while building.
 */
object BuildRunner {
    val steps = mutableStateListOf<String>()
    var running by mutableStateOf(false)
        internal set
    var result by mutableStateOf<BuildResult?>(null)
        internal set

    /** The request the service should run next. */
    @Volatile
    internal var pending: BuildRequest? = null
}

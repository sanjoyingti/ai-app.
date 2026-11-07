package com.aiapp.builder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shared Build Mode state: the agent timeline and the current project. The screen and the
 * background service both use it, and it's saved so it's still there after the app restarts.
 */
object BuildRunner {
    val timeline = mutableStateListOf<TimelineItem>()
    var running by mutableStateOf(false)
        internal set
    var project by mutableStateOf<Project?>(null)
        internal set

    /** The request the service should run next. */
    @Volatile
    internal var pending: BuildRequest? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false

    @Serializable
    private data class Saved(val project: Project? = null, val timeline: List<TimelineItem> = emptyList())

    private fun file(context: Context) = File(context.filesDir, "build_mode.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = runCatching { json.decodeFromString<Saved>(file(context).readText()) }.getOrNull() ?: return
        project = saved.project
        timeline.clear()
        timeline.addAll(saved.timeline)
    }

    fun save(context: Context) {
        runCatching { file(context).writeText(json.encodeToString(Saved(project, timeline.toList()))) }
    }

    /** Forget the current project and start fresh (the GitHub repo stays on your account). */
    fun newProject(context: Context) {
        if (running) return
        project = null
        timeline.clear()
        save(context)
    }
}

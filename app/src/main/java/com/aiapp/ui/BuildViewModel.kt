package com.aiapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildResult
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService

/** Thin wrapper: the build itself runs in [BuildService] so it survives closing the app. */
class BuildViewModel(private val app: Application) : AndroidViewModel(app) {
    val steps get() = BuildRunner.steps
    val running: Boolean get() = BuildRunner.running
    val result: BuildResult? get() = BuildRunner.result

    fun start(request: BuildRequest) = BuildService.start(app, request)

    fun stop() = BuildService.stop(app)
}

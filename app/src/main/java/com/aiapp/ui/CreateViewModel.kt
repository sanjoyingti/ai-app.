package com.aiapp.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.llm.Attachment
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.ProviderException
import com.aiapp.media.FreeImages
import com.aiapp.media.MediaGen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/** Create tab: free, no-key image generation. */
class CreateViewModel(private val app: Application) : AndroidViewModel(app) {
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var model by mutableStateOf("flux")
    /** "square", "portrait" or "landscape". */
    var shape by mutableStateOf("square")
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var resultUri by mutableStateOf<Uri?>(null)
        private set
    var resultBytes by mutableStateOf<ByteArray?>(null)
        private set

    /** A photo to base the new image on. */
    var reference by mutableStateOf<Attachment?>(null)
    var attachProgress by mutableStateOf<Float?>(null)
        private set

    private var job: Job? = null

    init {
        viewModelScope.launch {
            models = FreeImages.models()
            if (models.none { it.id == model }) model = models.firstOrNull()?.id ?: "flux"
        }
    }

    fun attach(uri: Uri) {
        attachProgress = 0f
        viewModelScope.launch {
            try {
                val a = com.aiapp.data.AttachmentStore.import(app, uri) { p -> viewModelScope.launch { attachProgress = p } }
                if (!a.isImage) error = "Attach a photo (JPG, PNG or WebP)." else {
                    reference = a
                    error = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Couldn't attach that file."
            } finally {
                attachProgress = null
            }
        }
    }

    fun generate(prompt: String) {
        if (busy || prompt.isBlank()) return
        val (w, h) = when (shape) {
            "portrait" -> 768 to 1344
            "landscape" -> 1344 to 768
            else -> 1024 to 1024
        }
        busy = true
        error = null
        resultUri = null
        resultBytes = null
        job = viewModelScope.launch {
            try {
                val bytes = FreeImages.image(prompt, model, w, h, reference) { status = it }
                status = "Saving to your gallery…"
                resultUri = MediaGen.saveToGallery(app, bytes, video = false)
                resultBytes = bytes
                status = "✓ Saved to Pictures/AIApp"
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Throwable) {
                status = null
                error = when {
                    e is ProviderException && e.code == 429 -> "The free image server is busy right now. Wait a few seconds and try again."
                    e is IOException -> "Network error. Check your connection and try again."
                    else -> e.message ?: "Something went wrong."
                }
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

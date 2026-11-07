package com.aiapp.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A model that runs on the phone with no internet, through Google's LiteRT-LM runtime. */
data class OfflineModel(
    val id: String,
    val name: String,
    /** Hugging Face repo holding the .litertlm file. */
    val repo: String,
    val approxSizeGb: Double,
    /** Phones with less RAM than this get the smaller model recommended. */
    val minRamGb: Int,
)

object OfflineCatalog {
    val models = listOf(
        OfflineModel(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B (best offline)",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            approxSizeGb = 3.7,
            minRamGb = 8,
        ),
        OfflineModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B (smaller phones)",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            approxSizeGb = 2.6,
            minRamGb = 4,
        ),
    )

    fun byId(id: String): OfflineModel? = models.firstOrNull { it.id == id }
}

/**
 * Downloads, stores and runs offline models. Model files live in the app's private storage, so
 * uninstalling the app removes them.
 */
class OfflineProvider(context: Context) : LlmProvider {
    override val id = ProviderId.OFFLINE

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "models").apply { mkdirs() }

    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var engineModelId: String? = null

    fun file(model: OfflineModel) = File(dir, "${model.id}.litertlm")

    fun isDownloaded(model: OfflineModel) = file(model).exists()

    /** Total RAM in GB, used to recommend a model. */
    fun deviceRamGb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Math.round(info.totalMem / 1_073_741_824.0).toInt()
    }

    fun recommended(): OfflineModel {
        val ram = deviceRamGb()
        return OfflineCatalog.models.firstOrNull { ram >= it.minRamGb } ?: OfflineCatalog.models.last()
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> =
        OfflineCatalog.models.map { m ->
            val suffix = if (isDownloaded(m)) "" else " · download in Settings"
            ModelInfo(m.id, m.name + suffix)
        }

    /** Finds the .litertlm file name in the Hugging Face repo, so a renamed file doesn't break downloads. */
    private suspend fun remoteFileName(model: OfflineModel): String {
        val info = Http.getJson("https://huggingface.co/api/models/${model.repo}", emptyMap())
        val names = (info["siblings"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("rfilename") as? JsonPrimitive)?.contentOrNull
        }.filter { it.endsWith(".litertlm") }
        // Prefer the general build over web or chip-specific ones.
        val chipTags = listOf("web", "qualcomm", "mediatek", "sm8", "mt6", "npu", "ios", "tpu")
        return names.firstOrNull { n -> chipTags.none { n.lowercase().contains(it) } }
            ?: names.firstOrNull()
            ?: throw IOException("No model file found in ${model.repo}")
    }

    /**
     * Downloads [model], resuming a partial download if one exists. [onProgress] gets 0..1.
     * Cancelling the coroutine stops the download and keeps the partial file for next time.
     */
    suspend fun download(model: OfflineModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(model)
        if (target.exists()) return@withContext
        val part = File(dir, "${model.id}.part")
        val name = remoteFileName(model)
        val url = "https://huggingface.co/${model.repo}/resolve/main/$name"
        val already = if (part.exists()) part.length() else 0L

        val request = Request.Builder().url(url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ProviderException(resp.code, "Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("Empty download.")
            val resumed = resp.code == 206
            val start = if (resumed) already else 0L
            val total = start + body.contentLength().coerceAtLeast(0L)
            RandomAccessFile(part, "rw").use { out ->
                if (!resumed) out.setLength(0)
                out.seek(start)
                val buf = ByteArray(1 shl 16)
                var written = start
                var lastReport = 0L
                body.byteStream().use { input ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0 && written - lastReport > 4_000_000) {
                            lastReport = written
                            onProgress(written.toFloat() / total)
                        }
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IOException("Couldn't save the model file.")
        onProgress(1f)
    }

    suspend fun delete(model: OfflineModel) = engineLock.withLock {
        if (engineModelId == model.id) closeEngine()
        file(model).delete()
        File(dir, "${model.id}.part").delete()
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
    }

    /** Loads the model once and keeps it in memory; tries the GPU first and falls back to the CPU. */
    private fun loadEngine(model: OfflineModel): Engine {
        engine?.let { if (engineModelId == model.id) return it }
        closeEngine()
        val path = file(model).absolutePath
        val cache = appContext.cacheDir.path
        val loaded = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cache)).also { it.initialize() }
        } catch (gpu: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cache)).also { it.initialize() }
        }
        engine = loaded
        engineModelId = model.id
        return loaded
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = flow {
        val m = OfflineCatalog.byId(model) ?: throw ProviderException(400, "Unknown offline model.")
        if (!isDownloaded(m)) {
            throw ProviderException(404, "Download ${m.name} in Settings first (about ${m.approxSizeGb} GB).")
        }
        val last = messages.lastOrNull { it.role == Role.USER } ?: return@flow
        val history = messages.dropLast(1).map {
            if (it.role == Role.USER) Message.user(it.text) else Message.model(it.text)
        }
        engineLock.withLock {
            val eng = loadEngine(m)
            val config = ConversationConfig(
                systemInstruction = Contents.of(system + effortHint(effort)),
                initialMessages = history,
            )
            eng.createConversation(config).use { conversation ->
                conversation.sendMessageAsync(last.text).collect { emit(it.toString()) }
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Small on-device models have no reasoning switch, so the Brain level becomes an instruction. */
    private fun effortHint(effort: Effort): String = when (effort) {
        Effort.LOW -> " Keep answers short and direct."
        Effort.HIGH -> " Think the problem through step by step before giving the final answer."
        Effort.MAX -> " Reason very carefully step by step, check your work, then give the final answer."
        else -> ""
    }
}

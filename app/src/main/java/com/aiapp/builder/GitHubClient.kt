package com.aiapp.builder

import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.zip.ZipInputStream

/** The bits of the GitHub REST API that Build Mode needs. */
class GitHubClient(private val token: String) {
    private val api = "https://api.github.com"
    private val jsonType = "application/json".toMediaType()

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement =
        withContext(Dispatchers.IO) {
            val rb = body?.toString()?.toRequestBody(jsonType)
            val req = request("$api$path").method(method, rb).build()
            Http.client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        (Http.json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
                    }.getOrNull() ?: "HTTP ${resp.code}"
                    throw ProviderException(resp.code, "GitHub: $msg")
                }
                if (text.isBlank()) JsonObject(emptyMap()) else Http.json.parseToJsonElement(text)
            }
        }

    private fun JsonElement.str(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonElement.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    suspend fun login(): String = call("GET", "/user").str("login") ?: throw ProviderException(401, "GitHub: bad token")

    data class Repo(val owner: String, val name: String, val branch: String, val url: String)

    suspend fun createRepo(name: String, description: String): Repo {
        val body = buildJsonObject {
            put("name", name)
            put("description", description.take(300))
            put("private", false) // public repos get free Actions minutes and public download links
            put("auto_init", true)
        }
        val r = call("POST", "/user/repos", body)
        return Repo(
            owner = r.obj("owner")?.let { (it["login"] as? JsonPrimitive)?.contentOrNull } ?: login(),
            name = r.str("name") ?: name,
            branch = r.str("default_branch") ?: "main",
            url = r.str("html_url") ?: "https://github.com",
        )
    }

    /**
     * Commits [files] (path → content, or null to delete) on top of the branch in one commit.
     * Returns the commit SHA.
     */
    suspend fun commit(
        repo: Repo,
        files: Map<String, ByteArray?>,
        message: String,
        /** Big files uploaded straight from storage, so they never have to fit in memory. */
        diskFiles: Map<String, java.io.File> = emptyMap(),
        onProgress: (Int) -> Unit = {},
    ): String {
        val base = "/repos/${repo.owner}/${repo.name}"
        // auto_init can take a moment before the branch exists.
        var ref: JsonElement? = null
        for (i in 0 until 10) {
            ref = runCatching { call("GET", "$base/git/ref/heads/${repo.branch}") }.getOrNull()
            if (ref != null) break
            kotlinx.coroutines.delay(1500)
        }
        val headSha = ref?.obj("object")?.let { (it["sha"] as? JsonPrimitive)?.contentOrNull }
            ?: throw ProviderException(500, "GitHub: branch ${repo.branch} not found")
        val baseTree = call("GET", "$base/git/commits/$headSha").obj("tree")
            ?.let { (it["sha"] as? JsonPrimitive)?.contentOrNull }

        var done = 0
        val entries = files.map { (path, bytes) ->
            if (++done % 50 == 0) onProgress(done)
            val blob = bytes?.let {
                call("POST", "$base/git/blobs", buildJsonObject {
                    put("content", Base64.getEncoder().encodeToString(it))
                    put("encoding", "base64")
                }).str("sha")!!
            }
            path to blob
        }
        val diskEntries = diskFiles.map { (path, file) ->
            if (++done % 50 == 0) onProgress(done)
            path to blobFromFile(base, file)
        }
        val allEntries = entries + diskEntries
        val tree = call("POST", "$base/git/trees", buildJsonObject {
            if (baseTree != null) put("base_tree", baseTree)
            putJsonArray("tree") {
                allEntries.forEach { (path, sha) ->
                    addJsonObject {
                        put("path", path)
                        put("mode", if (path == "gradlew" || path.endsWith("/gradlew") || path.endsWith(".sh")) "100755" else "100644")
                        put("type", "blob")
                        if (sha == null) put("sha", JsonNull) else put("sha", sha)
                    }
                }
            }
        }).str("sha")!!
        val commit = call("POST", "$base/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            putJsonArray("parents") { add(JsonPrimitive(headSha)) }
        }).str("sha")!!
        call("PATCH", "$base/git/refs/heads/${repo.branch}", buildJsonObject { put("sha", commit) })
        return commit
    }

    /** Creates a blob by streaming the file as base64 inside the JSON body (no big strings in memory). */
    private suspend fun blobFromFile(base: String, file: java.io.File): String = withContext(Dispatchers.IO) {
        val body = object : okhttp3.RequestBody() {
            override fun contentType() = jsonType
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8("{\"encoding\":\"base64\",\"content\":\"")
                val out = Base64.getEncoder().wrap(object : java.io.OutputStream() {
                    override fun write(b: Int) { sink.writeByte(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                    override fun close() = Unit
                })
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
                out.close()
                sink.writeUtf8("\"}")
            }
        }
        val req = request("$api$base/git/blobs").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ProviderException(r.code, "GitHub: couldn't upload ${file.name} (HTTP ${r.code})")
            (Http.json.parseToJsonElement(text).jsonObject["sha"] as? JsonPrimitive)?.contentOrNull
                ?: throw ProviderException(500, "GitHub: upload of ${file.name} failed")
        }
    }

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String)

    /** The Actions run started by the push of [sha], or null if it hasn't appeared yet. */
    /** The run of [workflowFile] (e.g. "build.yml") for [sha]; other workflows in the repo are ignored. */
    suspend fun runFor(repo: Repo, sha: String, workflowFile: String? = null): Run? {
        val runs = call("GET", "/repos/${repo.owner}/${repo.name}/actions/runs?head_sha=$sha&per_page=20")
            .let { (it as? JsonObject)?.get("workflow_runs") as? JsonArray } ?: return null
        val r = runs.mapNotNull { it as? JsonObject }.firstOrNull { run ->
            workflowFile == null || (run["path"] as? JsonPrimitive)?.contentOrNull?.endsWith(workflowFile) == true
        } ?: return null
        return Run(
            id = (r["id"] as? JsonPrimitive)?.longOrNull ?: return null,
            status = (r["status"] as? JsonPrimitive)?.contentOrNull ?: "queued",
            conclusion = (r["conclusion"] as? JsonPrimitive)?.contentOrNull,
            url = (r["html_url"] as? JsonPrimitive)?.contentOrNull ?: repo.url,
        )
    }

    /** Downloads the run's logs and keeps only the lines that explain a failure. */
    suspend fun failureSummary(repo: Repo, runId: Long): String = withContext(Dispatchers.IO) {
        val req = request("$api/repos/${repo.owner}/${repo.name}/actions/runs/$runId/logs").get().build()
        val lines = mutableListOf<String>()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext "Build failed (logs unavailable, HTTP ${resp.code})."
            ZipInputStream(resp.body!!.byteStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    zip.bufferedReader().readLines().forEach { lines += it }
                }
            }
        }
        extractErrors(lines)
    }

    suspend fun latestApkUrl(repo: Repo): String? {
        val rel = runCatching { call("GET", "/repos/${repo.owner}/${repo.name}/releases/latest") }.getOrNull() ?: return null
        val assets = (rel as? JsonObject)?.get("assets") as? JsonArray ?: return null
        return assets.mapNotNull { (it as? JsonObject)?.get("browser_download_url") as? JsonPrimitive }
            .map { it.content }.firstOrNull { it.endsWith(".apk") }
    }

    companion object {
        private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ")

        /** Keeps compiler errors and Gradle's "What went wrong" section, capped so it fits a prompt. */
        fun extractErrors(raw: List<String>): String {
            val lines = raw.map { it.replace(timestamp, "") }
            val picked = LinkedHashSet<String>()
            lines.forEachIndexed { i, l ->
                val t = l.trim()
                if (t.startsWith("e: ") || t.contains("error:", ignoreCase = true) ||
                    t.startsWith("ERROR:") || t.contains("Unresolved reference") ||
                    t.contains("AAPT: error")
                ) picked += t
                if (t.startsWith("* What went wrong")) {
                    lines.drop(i + 1).take(8).forEach { picked += it.trim() }
                }
            }
            val text = picked.filter { it.isNotBlank() }.joinToString("\n")
            return text.ifBlank { lines.takeLast(40).joinToString("\n") }.take(6000)
        }
    }
}

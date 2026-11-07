package com.aiapp.builder

import com.aiapp.llm.Http
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    /** Commits [files] (path → content) on top of the branch in one commit. Returns the commit SHA. */
    suspend fun commit(repo: Repo, files: Map<String, ByteArray>, message: String): String {
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

        val entries = files.map { (path, bytes) ->
            val blob = call("POST", "$base/git/blobs", buildJsonObject {
                put("content", Base64.getEncoder().encodeToString(bytes))
                put("encoding", "base64")
            }).str("sha")!!
            path to blob
        }
        val tree = call("POST", "$base/git/trees", buildJsonObject {
            if (baseTree != null) put("base_tree", baseTree)
            putJsonArray("tree") {
                entries.forEach { (path, sha) ->
                    addJsonObject {
                        put("path", path)
                        put("mode", if (path == "gradlew") "100755" else "100644")
                        put("type", "blob")
                        put("sha", sha)
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

    data class Run(val id: Long, val status: String, val conclusion: String?, val url: String)

    /** The Actions run started by the push of [sha], or null if it hasn't appeared yet. */
    suspend fun runFor(repo: Repo, sha: String): Run? {
        val runs = call("GET", "/repos/${repo.owner}/${repo.name}/actions/runs?head_sha=$sha&per_page=5")
            .let { (it as? JsonObject)?.get("workflow_runs") as? JsonArray } ?: return null
        val r = runs.firstOrNull() as? JsonObject ?: return null
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

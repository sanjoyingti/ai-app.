package com.aiapp.builder

import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.Http
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BuildRequest(
    /** The new app's description, or the change to make to [BuildRunner.project]. */
    val description: String,
    val appName: String,
    val provider: ProviderId,
    val model: String,
    val apiKey: String,
    val effort: Effort,
    val githubToken: String,
    /** Let the agent download files from sites outside the built-in safe list. */
    val allowOtherSites: Boolean,
    /** Continue this project instead of starting a new one. */
    val project: Project? = null,
)

data class BuildResult(
    val success: Boolean,
    val message: String,
    val repoUrl: String? = null,
    val apkUrl: String? = null,
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 */
class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
) {
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    private val packageName = req.project?.packageName ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName = req.project?.appName ?: req.appName

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    private var depsChanged = false

    private val maxFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (req.provider == ProviderId.OFFLINE) 2 else 0

    private fun step(kind: BuildEvent.Kind, text: String) = emit(BuildEvent(kind, text))

    suspend fun run(): BuildResult {
        step(BuildEvent.Kind.STEP, "Checking your GitHub token")
        gh.login()

        val existing = req.project
        val repo: GitHubClient.Repo
        var notes: List<String>
        if (existing == null) {
            step(BuildEvent.Kind.THINKING, "Planning and writing the app with ${req.provider.label}")
            notes = apply(ask(firstPrompt()))
            if (files.isEmpty()) {
                return BuildResult(false, "The model didn't return any files. Try again or pick a stronger model.")
            }
            val repoName = "$slug-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            step(BuildEvent.Kind.STEP, "Creating GitHub repo $repoName")
            repo = gh.createRepo(repoName, "Built by AI App: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            if ("app/src/main/AndroidManifest.xml" !in files) {
                pendingFiles["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(appName).toByteArray()
            }
        } else {
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
            notes = apply(ask(changePrompt()))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any files. Try describing the change differently.", repo.url, existing.lastApkUrl)
            }
        }
        saveProject(repo, null)

        var round = 0
        while (true) {
            if (depsChanged) {
                pendingFiles["app/build.gradle.kts"] =
                    ProjectTemplate.files(appName, packageName, deps.toList()).getValue("app/build.gradle.kts").toByteArray()
                depsChanged = false
            }
            step(BuildEvent.Kind.STEP, "Uploading ${pendingFiles.size} changed file${if (pendingFiles.size == 1) "" else "s"} to GitHub")
            val message = when {
                round > 0 -> "Fix build errors (round $round)"
                existing != null -> "Change: ${req.description.take(60)}"
                else -> "Initial app from AI App"
            }
            val sha = gh.commit(repo, pendingFiles.toMap(), message)
            pendingFiles.clear()

            step(BuildEvent.Kind.BUILD, "Building the APK on GitHub (usually 3–5 minutes)")
            val run = waitForRun(repo, sha)
                ?: return BuildResult(false, "GitHub didn't start a build. Check that your token has the 'workflow' scope.", repo.url)

            if (run.conclusion == "success") {
                step(BuildEvent.Kind.STEP, "Build succeeded. Getting the download link")
                var apk: String? = null
                for (i in 0 until 10) {
                    apk = gh.latestApkUrl(repo)
                    if (apk != null) break
                    delay(3000)
                }
                saveProject(repo, apk)
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(true, if (existing == null) "Your app is ready." else "Your changes are built.", repo.url, apk)
            }

            if (round >= maxFixRounds) {
                return BuildResult(false, "The build still fails after $round fix rounds. Try a higher Brain level or a stronger model.", repo.url)
            }
            round++
            val errors = gh.failureSummary(repo, run.id)
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}. Fixing (round $round of $maxFixRounds)")
            notes = apply(ask(fixPrompt(errors, notes)))
        }
    }

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) {
        onProject(
            Project(
                appName = appName,
                packageName = packageName,
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                repoUrl = repo.url,
                description = req.project?.description ?: req.description,
                files = files.toMap(),
                deps = deps.toList(),
                lastApkUrl = apk ?: req.project?.lastApkUrl,
            ),
        )
    }

    /**
     * Asks the model, letting it search the web or read pages before it writes files, and reports
     * each file as it starts streaming in.
     */
    private suspend fun ask(prompt: String): String {
        val effort = if (req.effort == Effort.AUTO) Effort.HIGH else req.effort
        val system = ProjectTemplate.systemPrompt(packageName) + ProjectTemplate.webInstructions
        val cap = if (req.provider == ProviderId.OFFLINE) 3_000 else 8_000
        val working = mutableListOf(ChatMessage(Role.USER, prompt))
        var steps = 0
        while (true) {
            val sb = StringBuilder()
            var scanned = 0
            Providers.get(req.provider).stream(req.apiKey, req.model, working, effort, system).collect { chunk ->
                sb.append(chunk)
                // Announce files as their headers arrive, like an IDE agent.
                val nl = sb.lastIndexOf("\n")
                if (nl > scanned) {
                    sb.substring(scanned, nl).lines().forEach { line ->
                        fileStart.find(line.trim())?.let { step(BuildEvent.Kind.FILE, "Writing ${it.groupValues[1].trim('`', ' ').substringAfterLast('/')}") }
                    }
                    scanned = nl
                }
            }
            val reply = sb.toString()
            if (steps >= MAX_WEB_STEPS || reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            val result = when {
                search != null -> {
                    step(BuildEvent.Kind.WEB, "Searching: $search")
                    "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                }
                fetch != null -> {
                    step(BuildEvent.Kind.WEB, "Reading ${fetch.removePrefix("https://").take(60)}")
                    "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                }
                else -> return reply
            }
            steps++
            working += ChatMessage(Role.ASSISTANT, reply.trim())
            working += ChatMessage(Role.USER, "[Tool result]\n$result\n\nNow continue: write the files in the required format.")
        }
    }

    private fun firstPrompt() = """
        Build this Android app: ${req.description}
        App name: $appName
    """.trimIndent()

    private fun StringBuilder.appendFiles() {
        var budget = 60_000
        files.forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget)
            budget -= c.length
            appendLine("=== FILE: $path ===")
            appendLine(c)
            appendLine("=== END FILE ===")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    private fun changePrompt() = buildString {
        appendLine("Here is an existing Android app, \"$appName\" (originally: ${req.project?.description}).")
        appendLine()
        appendLine("Current files:")
        appendFiles()
        appendLine()
        appendLine("Make this change: ${req.description}")
        appendLine()
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    private fun fixPrompt(errors: String, notes: List<String>) = buildString {
        appendLine("The app below failed to build. Fix it.")
        appendLine("App request: ${req.project?.description ?: req.description}")
        if (req.project != null) appendLine("Latest change requested: ${req.description}")
        appendLine()
        appendLine("Build errors:")
        appendLine(errors)
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes from the build tool:")
            notes.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Current files:")
        appendFiles()
        appendLine()
        appendLine("Reply with every file you change, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** Applies the model's output; returns notes to pass back to the model (skipped downloads etc.). */
    private suspend fun apply(output: String): List<String> {
        val notes = mutableListOf<String>()
        val parsed = parse(output)
        if (parsed.truncated) notes += "Your last reply was cut off. Send fewer or shorter files."
        parsed.files.forEach { (path, content) ->
            if (!path.startsWith("app/src/main/") || path.contains("..")) {
                notes += "Ignored $path: only files under app/src/main/ are allowed."
            } else if (content.trim() == "DELETE") {
                if (files.remove(path) != null) pendingFiles[path] = null
            } else if (files[path] != content) {
                files[path] = content
                pendingFiles[path] = content.toByteArray()
            }
        }
        parsed.deps.forEach { d ->
            val resolved = resolveDependency(d)
            if (resolved == null) {
                notes += "Dependency $d was not found on Maven; use a different library."
            } else if (resolved !in deps) {
                deps.removeAll { it.substringBeforeLast(':') == resolved.substringBeforeLast(':') }
                deps += resolved
                depsChanged = true
                step(BuildEvent.Kind.LIBRARY, "Added library $resolved")
            }
        }
        parsed.downloads.forEach { (url, path) -> download(url, path)?.let { notes += it } }
        return notes
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha) }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            delay(10_000)
        }
        return run
    }

    // ---- Internet access: libraries and files ----

    private val repos = listOf(
        "https://dl.google.com/android/maven2",
        "https://repo1.maven.org/maven2",
        "https://jitpack.io",
    )

    private val safeHosts = listOf(
        "github.com", "raw.githubusercontent.com", "githubusercontent.com", "fonts.google.com",
        "fonts.gstatic.com", "upload.wikimedia.org", "commons.wikimedia.org", "repo1.maven.org",
        "dl.google.com", "jitpack.io", "unpkg.com", "cdn.jsdelivr.net",
    )

    private suspend fun httpText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    /** Checks the library exists; replaces "latest", "+" or a missing version with the newest release. */
    private suspend fun resolveDependency(coord: String): String? {
        val parts = coord.trim().split(":")
        if (parts.size < 2) return null
        val (group, artifact) = parts
        val version = parts.getOrNull(2)?.trim().orEmpty()
        val path = group.replace('.', '/') + "/" + artifact
        for (base in repos) {
            if (version.isNotEmpty() && version != "latest" && !version.contains('+')) {
                if (httpText("$base/$path/$version/$artifact-$version.pom") != null) return "$group:$artifact:$version"
            }
            val meta = httpText("$base/$path/maven-metadata.xml") ?: continue
            val latest = Regex("<release>([^<]+)</release>").find(meta)?.groupValues?.get(1)
                ?: Regex("<latest>([^<]+)</latest>").find(meta)?.groupValues?.get(1)
                ?: Regex("<version>([^<]+)</version>").findAll(meta).lastOrNull()?.groupValues?.get(1)
            if (latest != null) return "$group:$artifact:$latest"
        }
        return null
    }

    private val resourceName = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9]+)?$")

    /** Downloads a file into the project; returns a note for the model if it was skipped. */
    private suspend fun download(url: String, path: String): String? {
        val okPath = path.startsWith("app/src/main/res/raw/") || path.startsWith("app/src/main/res/drawable/") ||
            path.startsWith("app/src/main/res/font/") || path.startsWith("app/src/main/assets/")
        if (!okPath || path.contains("..")) return "Skipped download to $path: use res/raw, res/drawable, res/font or assets."
        if (!path.startsWith("app/src/main/assets/") && !resourceName.matches(path.substringAfterLast('/'))) {
            return "Skipped $path: resource names must be lowercase letters, digits and underscores."
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || !url.startsWith("https://")) return "Skipped $url: only https links work."
        val safe = safeHosts.any { host == it || host.endsWith(".$it") }
        if (!safe && !req.allowOtherSites) {
            step(BuildEvent.Kind.DOWNLOAD, "Blocked download from $host (turn on \"Allow other sites\")")
            return "Download from $host was blocked. Use a file from GitHub, Google Fonts or Wikimedia, or draw it in code."
        }
        step(BuildEvent.Kind.DOWNLOAD, "Downloading ${path.substringAfterLast('/')} from $host")
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val len = r.body?.contentLength() ?: -1
                    if (len > MAX_DOWNLOAD) return@use null
                    r.body?.bytes()?.takeIf { it.size <= MAX_DOWNLOAD }
                }
            }.getOrNull()
        } ?: return "Download failed or was too large (max 15 MB): $url"
        pendingFiles[path] = bytes
        return null
    }

    // ---- Output parsing ----

    data class Parsed(
        val files: List<Pair<String, String>>,
        val deps: List<String>,
        val downloads: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024
        private const val MAX_WEB_STEPS = 3
        private val fileStart = Regex("^=+\\s*FILE:\\s*(.+?)\\s*=*\\s*$")
        private val fileEnd = Regex("^=+\\s*END FILE\\s*=*\\s*$")
        private val depLine = Regex("^=+\\s*DEPENDENCY:\\s*(.+?)\\s*=*\\s*$")
        private val downloadLine = Regex("^=+\\s*DOWNLOAD:\\s*(\\S+)\\s*->\\s*(\\S+?)\\s*=*\\s*$")

        fun parse(text: String): Parsed {
            val files = mutableListOf<Pair<String, String>>()
            val deps = mutableListOf<String>()
            val downloads = mutableListOf<Pair<String, String>>()
            var path: String? = null
            val body = StringBuilder()
            for (raw in text.lines()) {
                val line = raw.trim()
                val current = path
                if (current == null) {
                    val start = fileStart.find(line)
                    val dep = depLine.find(line)
                    val dl = downloadLine.find(line)
                    when {
                        start != null -> {
                            path = start.groupValues[1].trim('`', ' ')
                            body.clear()
                        }
                        dep != null -> deps.add(dep.groupValues[1].trim('`', ' '))
                        dl != null -> downloads.add(dl.groupValues[1] to dl.groupValues[2])
                    }
                } else if (fileEnd.matches(line)) {
                    files.add(current to stripFences(body.toString()))
                    path = null
                } else {
                    body.appendLine(raw)
                }
            }
            return Parsed(files, deps, downloads, truncated = path != null)
        }

        /** Small models often wrap code in ``` fences inside the file block. */
        private fun stripFences(s: String): String {
            val lines = s.trimEnd().lines().toMutableList()
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
            if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.trim() == "```") lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n") + "\n"
        }
    }
}

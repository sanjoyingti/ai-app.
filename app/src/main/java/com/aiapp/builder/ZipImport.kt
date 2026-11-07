package com.aiapp.builder

import java.io.File
import java.util.zip.ZipInputStream

/** Reads an uploaded Android Studio project from a .zip. */
object ZipImport {
    private const val MAX_TOTAL = 150L * 1024 * 1024
    private const val MAX_FILE = 50L * 1024 * 1024
    private const val MAX_FILES = 3000

    private val skipDirs = listOf("build/", ".gradle/", ".idea/", ".kotlin/", ".cxx/", ".externalNativeBuild/", "captures/", "__MACOSX/", ".git/")
    private val skipFiles = listOf("local.properties", ".DS_Store")

    val textExtensions = setOf(
        "kt", "kts", "java", "xml", "gradle", "properties", "json", "md", "txt", "pro", "toml", "yml", "yaml",
        "cfg", "html", "css", "js", "c", "cpp", "h", "hpp", "cmake", "aidl", "gitignore",
    )

    fun isText(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase() in textExtensions || name == "gradlew" || name == ".gitignore"
    }

    data class Imported(val files: Map<String, ByteArray>, val name: String, val skipped: Int)

    fun read(zip: File, fallbackName: String): Imported {
        val raw = linkedMapOf<String, ByteArray>()
        var total = 0L
        var skipped = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/').trimStart('/')
                if (e.isDirectory || path.contains("../")) continue
                if (skipDirs.any { path.startsWith(it) || path.contains("/$it") } || path.substringAfterLast('/') in skipFiles ||
                    path.endsWith(".apk") || path.endsWith(".aab")
                ) {
                    skipped++
                    continue
                }
                val bytes = z.readBytes()
                if (bytes.size > MAX_FILE) {
                    skipped++
                    continue
                }
                total += bytes.size
                if (total > MAX_TOTAL) throw IllegalArgumentException("The project is larger than 150 MB (without build folders).")
                raw[path] = bytes
                if (raw.size > MAX_FILES) throw IllegalArgumentException("The project has more than $MAX_FILES files.")
            }
        }
        if (raw.isEmpty()) throw IllegalArgumentException("The zip is empty.")

        // The project root is the shallowest folder holding settings.gradle(.kts).
        val settings = raw.keys.filter { it.substringAfterLast('/').startsWith("settings.gradle") }
            .minByOrNull { it.count { c -> c == '/' } }
            ?: throw IllegalArgumentException("This doesn't look like an Android Studio project (no settings.gradle found).")
        val prefix = settings.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val files = raw.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            .filterKeys { !it.startsWith(".github/workflows/ai-app-build") }

        val settingsText = files[settings.removePrefix(prefix)]?.toString(Charsets.UTF_8).orEmpty()
        val name = Regex("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']").find(settingsText)?.groupValues?.get(1)
            ?: fallbackName.removeSuffix(".zip")
        return Imported(files, name, skipped)
    }

    /** A short summary (file tree + key files) so any chat model can read an uploaded project. */
    fun summary(zip: File, maxChars: Int = 150_000): String {
        val sb = StringBuilder()
        val tree = mutableListOf<String>()
        val contents = mutableListOf<Pair<String, String>>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val path = e.name.replace('\\', '/')
                if (e.isDirectory || skipDirs.any { path.startsWith(it) || path.contains("/$it") }) continue
                tree += path
                if (isText(path) && tree.size < 2000) {
                    val bytes = z.readBytes()
                    if (bytes.size < 60_000) contents += path to bytes.toString(Charsets.UTF_8)
                }
            }
        }
        sb.append("Project files (${tree.size}):\n")
        tree.take(400).forEach { sb.append("  ").append(it).append('\n') }
        if (tree.size > 400) sb.append("  …\n")
        // Build files and sources first.
        val ordered = contents.sortedBy { (p, _) ->
            when {
                p.contains("gradle") -> 0
                p.endsWith(".kt") || p.endsWith(".java") -> 1
                p.endsWith("AndroidManifest.xml") -> 1
                else -> 2
            }
        }
        for ((p, c) in ordered) {
            if (sb.length + c.length > maxChars) break
            sb.append("\n--- ").append(p).append(" ---\n").append(c).append('\n')
        }
        return sb.toString()
    }
}

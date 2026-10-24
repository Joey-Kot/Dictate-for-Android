package com.joeykot.dictate.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class Diagnostics(context: Context) {
    private val logFile = File(context.filesDir, "diagnostics.log")
    private val entries = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        // The diagnostics panel must retain the previous process's failures.
        // Stream the file so an older/larger log does not require an unbounded read.
        runCatching {
            if (logFile.exists()) logFile.useLines { lines ->
                lines.forEach { line ->
                    entries.addLast(line)
                    while (entries.size > MAX_MEMORY_ENTRIES) entries.removeFirst()
                }
            }
        }
    }

    @Synchronized
    fun info(stage: String, message: String) = append("INFO", stage, message)

    @Synchronized
    fun error(stage: String, message: String) = append("ERROR", stage, message)

    @Synchronized
    fun snapshot(maxEntries: Int = MAX_MEMORY_ENTRIES): String = entries.takeLast(maxEntries).joinToString("\n")

    @Synchronized
    fun clear() {
        entries.clear()
        if (logFile.exists()) logFile.writeText("")
    }

    fun sanitize(
        value: String,
        maxLength: Int = 2_000,
        secrets: Collection<String> = emptyList(),
    ): String {
        val secretRedacted = secrets
            .asSequence()
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
            .fold(value) { current, secret -> current.replace(secret, "[REDACTED]") }
        val redacted = secretRedacted
            .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
            .replace(
                Regex("(?i)(api[_ -]?key[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\\"'\\s,}]+"),
                "$1[REDACTED]",
            )
        return if (redacted.length <= maxLength) redacted else redacted.take(maxLength) + "…"
    }

    private fun append(level: String, stage: String, rawMessage: String) {
        val entry = "${timestampFormat.format(Date())} $level [$stage] ${sanitize(rawMessage)}"
        entries.addLast(entry)
        while (entries.size > MAX_MEMORY_ENTRIES) entries.removeFirst()
        Log.println(if (level == "ERROR") Log.ERROR else Log.INFO, TAG, entry)
        runCatching {
            rotateIfNeeded()
            logFile.appendText(entry + "\n")
        }.onFailure {
            Log.e(TAG, "Could not persist diagnostics (${it.javaClass.simpleName})")
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_FILE_BYTES) return
        val retained = logFile.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
        logFile.writeText(retained.substringAfter('\n', retained))
    }

    private companion object {
        const val TAG = "Dictate"
        const val MAX_MEMORY_ENTRIES = 2_000
        const val MAX_FILE_BYTES = 512L * 1024L
    }
}

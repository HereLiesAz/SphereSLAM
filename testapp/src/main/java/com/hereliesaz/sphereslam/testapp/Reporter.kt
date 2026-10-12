package com.hereliesaz.sphereslam.testapp

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What a run reports, besides the checks. */
class RunInfo(
    val runId: String,
    val build: String,
    val libraryVersion: String,
    val appVersion: String,
    val packageName: String,
    val brand: String,
    val model: String,
    val android: String,
    val sdk: Int,
    val startedAt: String,
)

/** The report body the workflows Worker's `/test-report/<repo>` route takes (pure, unit-tested). */
object ReportJson {
    fun build(info: RunInfo, checks: List<Check>, updatedAt: String, notes: String, log: String): String {
        val sb = StringBuilder()
        sb.append('{')
        field(sb, "packageName", info.packageName); sb.append(',')
        field(sb, "runId", info.runId); sb.append(',')
        field(sb, "build", info.build); sb.append(',')
        field(sb, "libraryVersion", info.libraryVersion); sb.append(',')
        field(sb, "appVersion", info.appVersion); sb.append(',')
        sb.append("\"device\":{")
        field(sb, "brand", info.brand); sb.append(',')
        field(sb, "model", info.model); sb.append(',')
        field(sb, "android", info.android); sb.append(',')
        field(sb, "sdk", info.sdk.toString())
        sb.append("},")
        field(sb, "startedAt", info.startedAt); sb.append(',')
        field(sb, "updatedAt", updatedAt); sb.append(',')
        field(sb, "notes", notes); sb.append(',')
        sb.append("\"checks\":[")
        checks.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append('{')
            field(sb, "id", c.id); sb.append(',')
            field(sb, "title", c.title); sb.append(',')
            field(sb, "status", c.status.name); sb.append(',')
            field(sb, "evidence", c.evidence)
            sb.append('}')
        }
        sb.append("],")
        field(sb, "log", log)
        sb.append('}')
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, value: String) {
        sb.append('"').append(name).append("\":")
        quote(sb, value)
    }

    fun quote(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/**
 * Sends the run whenever it changes: [schedule] after each change (debounced, so a burst of
 * verdicts is one request), [flush] when the app leaves the foreground. The Worker files every send
 * of one run into the same issue comment, so resending is cheap and idempotent.
 */
class Reporter(
    private val url: String,
    key: String,
    private val body: () -> String,
    private val onResult: (ok: Boolean, detail: String) -> Unit,
) {
    private val auth = "Basic " + Base64.getEncoder().encodeToString("testapp:$key".toByteArray())
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-report").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private var lastSent: String? = null

    @Synchronized
    fun schedule(delayMs: Long = DEBOUNCE_MS) {
        pending?.cancel(false)
        pending = executor.schedule({ send() }, delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() = schedule(0L)

    private fun send() {
        val json = body()
        if (json == lastSent) return
        val result = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                c.doOutput = true
                c.setRequestProperty("Authorization", auth)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
                c.responseCode to (runCatching { (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull() ?: "")
            } finally {
                c.disconnect()
            }
        }
        result.onSuccess { (code, text) ->
            val ok = code in 200..299
            if (ok) lastSent = json
            onResult(ok, "HTTP $code ${text.take(80)}")
        }.onFailure { onResult(false, it.javaClass.simpleName + ": " + (it.message ?: "")) }
    }

    fun close() = executor.shutdown()

    companion object {
        const val DEBOUNCE_MS = 3_000L
    }
}

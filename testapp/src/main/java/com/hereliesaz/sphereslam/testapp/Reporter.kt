package com.hereliesaz.sphereslam.testapp

import java.io.File
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

/** A table for the report (a tuning sweep's results): plain text cells, rendered escaped by the Worker. */
class ReportTable(val title: String, val columns: List<String>, val rows: List<List<String>>)

/** The report body the workflows Worker's `/test-report/<repo>` route takes (pure, unit-tested). */
object ReportJson {
    fun build(
        info: RunInfo,
        checks: List<Check>,
        updatedAt: String,
        notes: String,
        log: String,
        tables: List<ReportTable> = emptyList(),
    ): String {
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
        if (tables.isNotEmpty()) {
            sb.append("\"tables\":[")
            tables.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append('{')
                field(sb, "title", t.title); sb.append(',')
                sb.append("\"columns\":"); strings(sb, t.columns); sb.append(',')
                sb.append("\"rows\":[")
                t.rows.forEachIndexed { j, row -> if (j > 0) sb.append(','); strings(sb, row) }
                sb.append("]}")
            }
            sb.append("],")
        }
        field(sb, "log", log)
        sb.append('}')
        return sb.toString()
    }

    private fun strings(sb: StringBuilder, values: List<String>) {
        sb.append('[')
        values.forEachIndexed { i, v -> if (i > 0) sb.append(','); quote(sb, v) }
        sb.append(']')
    }

    /** The run's notes with a crash, when there was one, at the top where the issue shows it. */
    fun notesWithCrash(notes: String, crash: String?): String {
        if (crash.isNullOrBlank()) return notes
        val head = crash.lineSequence().take(CRASH_HEAD_LINES).joinToString("\n")
        return "THE APP CRASHED\n$head" + if (notes.isBlank()) "" else "\n\n$notes"
    }

    /** The log with the full crash trace after it, outside the line limit so it can't be pushed out. */
    fun logWithCrash(log: String, crash: String?): String =
        if (crash.isNullOrBlank()) log else "$log\n--- crash ---\n$crash"

    private const val CRASH_HEAD_LINES = 4

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
 * Sends the run whenever it changes: [schedule] after each change (debounced, so a burst of verdicts
 * is one request), [flush] when the app leaves the foreground. The Worker files every send of one run
 * into the same issue comment, so resending is cheap and idempotent.
 *
 * Nothing is lost to a dead network or a dead process: every body is written to [dir] before it is
 * posted ([LAST_FILE]) and marked delivered only on a 2xx ([PENDING_FILE] removed). A failed send
 * retries on its own with backoff, and [resendPending] sends an undelivered body at the next launch.
 * [body] takes the `updatedAt` stamp; the stamp-free body (`body("")`) is what decides whether
 * anything changed since the last delivery.
 */
class Reporter(
    private val url: String,
    key: String,
    private val dir: File,
    private val body: (updatedAt: String) -> String,
    private val onResult: (ok: Boolean, detail: String) -> Unit,
) {
    private val auth = authHeader(key)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "test-report").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var lastDelivered: String? = null
    private var failures = 0

    @Synchronized
    fun schedule(delayMs: Long = DEBOUNCE_MS) {
        if (executor.isShutdown) return
        pending?.cancel(false)
        pending = executor.schedule({ send() }, delayMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun flush() = schedule(0L)

    private fun send() {
        val unstamped = body("")
        if (unstamped == lastDelivered) return
        val json = body(java.time.Instant.now().toString())
        persist(dir, json)
        val (ok, detail) = post(url, auth, json)
        if (ok) {
            lastDelivered = unstamped
            failures = 0
            markDelivered(dir, runIdOf(json))
        } else {
            failures++
            schedule(retryDelayMs(failures))
        }
        onResult(ok, detail)
    }

    fun close() = executor.shutdown()

    companion object {
        const val DEBOUNCE_MS = 3_000L
        const val LAST_FILE = "report-last.json"
        const val PENDING_FILE = "report-pending"

        /** 10 s, 20 s, 40 s … capped at 5 min. */
        fun retryDelayMs(failures: Int): Long = minOf(10_000L shl (failures - 1).coerceIn(0, 5), 300_000L)

        fun authHeader(key: String) = "Basic " + Base64.getEncoder().encodeToString("testapp:$key".toByteArray())

        private val RUN_ID_RE = Regex("\"runId\":\"([^\"]*)\"")

        fun runIdOf(json: String): String = RUN_ID_RE.find(json)?.groupValues?.get(1).orEmpty()

        /**
         * Writes [json] as the last body and marks its run undelivered (safe from a crash handler).
         * The marker holds the run id, so a late delivery of an older run can't clear a newer one's.
         */
        fun persist(dir: File, json: String) {
            runCatching {
                File(dir, LAST_FILE).writeText(json)
                File(dir, PENDING_FILE).writeText(runIdOf(json))
            }
        }

        /** Clears the undelivered marker if it still belongs to [runId]. */
        fun markDelivered(dir: File, runId: String) {
            runCatching {
                val f = File(dir, PENDING_FILE)
                if (f.exists() && f.readText() == runId) f.delete()
            }
        }

        /** The last body written, or null. */
        fun lastBody(dir: File): String? = runCatching { File(dir, LAST_FILE).readText() }.getOrNull()

        fun hasPending(dir: File): Boolean = File(dir, PENDING_FILE).exists()

        /** One POST; (2xx, "HTTP <code> <reply>") or (false, the error). */
        fun post(url: String, auth: String, json: String, timeoutMs: Int = 20_000): Pair<Boolean, String> = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = minOf(10_000, timeoutMs)
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Authorization", auth)
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
                val code = c.responseCode
                val text = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull() ?: ""
                (code in 200..299) to "HTTP $code ${text.take(80)}"
            } finally {
                c.disconnect()
            }
        }.getOrElse { false to (it.javaClass.simpleName + ": " + (it.message ?: "")) }

        /** At launch: sends the previous process's undelivered body, if any, on a background thread. */
        fun resendPending(dir: File, url: String, key: String) {
            if (!hasPending(dir)) return
            val json = lastBody(dir) ?: return
            val runId = runIdOf(json)
            Thread({ if (post(url, authHeader(key), json).first) markDelivered(dir, runId) }, "test-report-resend").apply { isDaemon = true }.start()
        }
    }
}

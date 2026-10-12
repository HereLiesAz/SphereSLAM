package com.hereliesaz.sphereslam.testapp

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

class TestApp : Application() {
    /** The run in progress. Process-scoped, so a recreated activity carries on with the same run. */
    lateinit var run: Run

    /** Why OpenCV didn't load, or null; the activity puts it in the run's log. */
    var openCvError: String? = null
        private set

    /** Whether the first activity has handled what the previous process left (crash, unsent report). */
    var launchHandled = false

    override fun onCreate() {
        super.onCreate()
        run = Run.start(this)
        CrashLog.install(this)
        // Flow tracking and tile localization need OpenCV's native library (README: Matching speed).
        runCatching { System.loadLibrary("opencv_java5") }.onFailure {
            openCvError = "${it.javaClass.simpleName}: ${it.message}"
            Log.w("SphereSLAM-Test", "OpenCV not loaded; flow and tiles disable themselves", it)
        }
    }
}

/** One run: its identity, verdicts, place in the guide, log and notes. "Start over" makes a new one. */
class Run(val info: RunInfo) {
    val checklist = Checklist()
    val guide = Guide()
    private val logLines = ArrayDeque<String>()
    private var logChars = 0
    @Volatile var notes: String = ""

    fun log(line: String) {
        Log.d("SphereSLAM-Test", line)
        synchronized(logLines) {
            logLines.addLast("${Instant.now()} $line")
            logChars += logLines.last.length + 1
            // Oldest first to go: the report must fit the Worker's 128 KB body limit.
            while (logLines.size > MAX_LOG_LINES || logChars > MAX_LOG_CHARS) logChars -= logLines.removeFirst().length + 1
        }
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    /** The report body; [crash] (a stack trace) goes in the notes' head and after the log. */
    fun reportBody(updatedAt: String, crash: String? = null): String = synchronized(checklist) {
        ReportJson.build(
            info, checklist.checks, updatedAt,
            ReportJson.notesWithCrash(notes, crash),
            ReportJson.logWithCrash(logText(), crash),
        )
    }

    companion object {
        const val MAX_LOG_LINES = 400
        /** The log's size cap; the Worker shows its newest 50 000 characters in the issue. */
        const val MAX_LOG_CHARS = 60_000

        fun start(context: Context): Run = Run(
            RunInfo(
                runId = UUID.randomUUID().toString(),
                build = BuildConfig.SPHERESLAM_BUILD,
                libraryVersion = BuildConfig.SPHERESLAM_VERSION,
                appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                packageName = context.packageName,
                brand = Build.MANUFACTURER,
                model = Build.MODEL,
                android = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                startedAt = Instant.now().toString(),
            ),
        )
    }
}

/**
 * Gets a crash into the report of the run it ended, not lost and not filed under the next one.
 *
 * - An uncaught exception: the handler writes the crashed run's body, with the trace, as the
 *   undelivered report and tries to post it at once (a few seconds at most); whatever doesn't get
 *   out goes at the next launch ([Reporter.resendPending]).
 * - A native crash or an ANR never reaches that handler. At the next launch [recoverExit] reads the
 *   system's record of how the process ended (API 30+) and adds it, with its trace where the system
 *   kept one, to the last report the dead run wrote.
 */
object CrashLog {
    private const val PREFS = "crash-log"
    private const val LAST_EXIT_MS = "last-exit-ms"
    private const val MAX_TRACE_CHARS = 8_000
    private const val SEND_TIMEOUT_MS = 3_000L

    /** Set by the activity: posts are skipped until it is (no URL yet). */
    @Volatile var endpoint: Pair<String, String>? = null

    fun install(app: TestApp) {
        val dir = app.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = "on thread ${thread.name}\n" + error.stackTraceToString().take(MAX_TRACE_CHARS)
                val json = app.run.reportBody(Instant.now().toString(), crash = trace)
                Reporter.persist(dir, json)
                endpoint?.let { (url, key) ->
                    val sender = Thread {
                        if (Reporter.post(url, Reporter.authHeader(key), json, SEND_TIMEOUT_MS.toInt()).first) {
                            Reporter.markDelivered(dir, Reporter.runIdOf(json))
                        }
                    }
                    sender.start()
                    sender.join(SEND_TIMEOUT_MS)
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * How the previous process ended, if it was a native crash or an ANR not yet reported: appended to
     * that run's last report and marked undelivered. Returns a line for the new run's log, or null.
     */
    fun recoverExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(LAST_EXIT_MS, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1) }.getOrNull()?.firstOrNull() ?: return null
        if (exit.timestamp <= seen) return null
        prefs.edit().putLong(LAST_EXIT_MS, exit.timestamp).apply()
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR (the app stopped responding)"
            else -> return null // Java crashes are filed by the handler; other exits are not failures.
        }
        val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_TRACE_CHARS) } }.getOrNull()
        val detail = "$kind at ${Instant.ofEpochMilli(exit.timestamp)}: ${exit.description ?: "no description"}" +
            (trace?.let { "\n$it" } ?: "\n(no trace kept by the system)")
        val dir = context.filesDir
        val last = Reporter.lastBody(dir) ?: return "previous run ended in a $kind; it had no report to attach it to"
        runCatching {
            val body = JSONObject(last)
            body.put("notes", ReportJson.notesWithCrash(body.optString("notes"), detail))
            body.put("log", ReportJson.logWithCrash(body.optString("log"), detail))
            body.put("updatedAt", Instant.now().toString())
            Reporter.persist(dir, body.toString())
        }
        return "previous run ${Reporter.runIdOf(last)} ended in a $kind; added to its report"
    }
}

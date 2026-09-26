package dev.devcenter.rfid_kit.bridge

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the stack trace of a fatal crash so the NEXT app start can report it.
 *
 * A crash inside the Zebra SDK's callback threads kills the process outright —
 * Android shows "<app> keeps stopping" and everything about the cause dies with
 * it, leaving you to guess from the outside. Writing the trace to disk on the
 * way down and handing it to Dart on the next launch turns that guesswork into
 * a plain answer: no cable, no logcat, no access to the device needed.
 */
object CrashLog {

    private const val FILE_NAME = "rfid_kit_last_crash.txt"

    @Volatile
    private var installed = false

    /** Chain our writer in front of Android's own handler, which still runs. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = StringWriter()
            error.printStackTrace(PrintWriter(trace))
            // Mark whether this actually takes the app down, so the report can
            // say "the app closed" or "the reader library threw but the app
            // carried on" — two very different things for whoever is testing.
            val fatal = !isVendorThread(trace.toString())
            try {
                File(appContext.filesDir, FILE_NAME).writeText(
                    "fatal: $fatal\nthread: ${thread.name}\n$trace",
                )
            } catch (_: Throwable) {
                // Never throw on the way down.
            }
            // A throw on one of the vendor SDK's own worker threads (its serial
            // reader raising "Already running", for instance) is not the app's
            // failure and does not corrupt the app's state — yet the default
            // handler kills the whole process for it, in front of the operator.
            // Let that thread die alone; the trace is on disk and gets reported
            // at the next launch either way. Anything from the app's own code
            // still takes the normal path, because silently surviving a real bug
            // is worse.
            if (fatal) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    private fun isVendorThread(trace: String): Boolean =
        trace.contains("com.zebra.rfid") || trace.contains("com.zebra.sdk")

    /** Returns the last crash (once) and clears it. */
    fun consume(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (_: Throwable) {
            null
        }
        try {
            file.delete()
        } catch (_: Throwable) {
            // best effort — a repeated report beats a lost one
        }
        return text?.takeIf { it.isNotBlank() }
    }
}

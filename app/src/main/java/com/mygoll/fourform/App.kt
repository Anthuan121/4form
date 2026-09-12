package com.mygoll.fourform

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's first job is to say why it died, without a cable and without adb.
 * The handler writes to filesDir/ultimo-crash.txt and THEN forwards to the previous
 * handler, so Android still proceeds with its standard crash dialog.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // migration from 242: the old report (v0.1/v0.2) wrote out entered VALUES; the
        // new diagnostic doesn't, and the old file doesn't need to keep sitting on disk
        File(filesDir, "ultimo-relatorio.json").delete()
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                val carimbo = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(filesDir, ARQUIVO_CRASH).writeText(
                    "quando: $carimbo\n" +
                        "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "thread: ${thread.name}\n\n" +
                        erro.stackTraceToString()
                )
            } catch (_: Throwable) {
                // logging the crash can never cause another crash
            }
            anterior?.uncaughtException(thread, erro)
        }
    }

    companion object {
        const val ARQUIVO_CRASH = "ultimo-crash.txt"
    }
}

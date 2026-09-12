package com.mygoll.fourform

import com.mygoll.fourform.agent.Probe
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads the diagnostic to the bench probe. TEST BUILD ONLY (standalone brief 09/10).
 *
 * Two independent locks, on purpose:
 *   1. android.permission.INTERNET only exists in app/src/debug/AndroidManifest.xml;
 *   2. the BuildConfig.DEBUG guard below, which keeps release from even trying.
 * One lock lives in the build system, the other in the code. If someone moves the manifest
 * without noticing, the second one still holds.
 *
 * What gets uploaded is the SAME json as the local file, and it was already built to not
 * carry field values or anything from a password field (Diagnostics.kt, with a test proving
 * it). If it did, it wouldn't upload: the product that promises not to leak would have
 * leaked in its own test.
 *
 * HttpURLConnection and not a library: the project has zero runtime dependencies and this
 * is a 6-line PUT. A raw Thread and not a coroutine/executor for the same reason. It's a
 * one-off call per scan, not a pipeline.
 */
object Bench {

    fun enviar(json: String, quandoMs: Long) {
        if (!BuildConfig.DEBUG) return
        // Own thread: networking on the main thread throws NetworkOnMainThreadException, and
        // here the main thread is the accessibility service's. Freezing it freezes the fill
        // RIGHT IN FRONT of the person, which is the one thing the demo cannot do.
        Thread {
            runCatching {
                val con = URL(Probe.url(quandoMs)).openConnection() as HttpURLConnection
                con.requestMethod = "PUT"
                con.doOutput = true
                // Short timeouts because the probe is a comfort, not a requirement: bad 3G
                // on hackathon day cannot turn into half a minute of a hung thread.
                con.connectTimeout = 4000
                con.readTimeout = 4000
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                con.responseCode // triggers the send
                con.disconnect()
            }
            // runCatching with no else on purpose: probe offline, no network, or DNS down
            // NEVER brings down the fill. Silent failure is the right behavior for
            // telemetry. The app is the product, the probe is the microscope.
        }.start()
    }
}

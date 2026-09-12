package com.mygoll.fourform

import com.mygoll.fourform.agent.Llm
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The networking for the open-ended response (brief 245). Thin on purpose: POST, 200, or
 * exception. Who decides what to do with the body (or the failure) is Llm.avaliar, pure
 * and tested.
 *
 * ALWAYS runs off the main thread (FourFormService.consultarLlm): the main thread here is
 * the accessibility service's, and freezing it freezes the fill right in front of the
 * person. INTERNET only exists in the debug variant. In release this call fails instantly
 * and the field stays open, which is the intended combined degradation.
 */
object LlmBridge {

    /** Empty when whoever cloned the repo has no local.properties: fails instantly, field stays open. */
    fun temPonte(): Boolean = BuildConfig.LLM_URL.isNotBlank()

    // brief's network ceiling: <= 20s total (4s connect + 16s read)
    fun chamar(corpo: String): String {
        if (!temPonte()) throw IOException("bridge not configured (local.properties missing)")
        val con = URL(BuildConfig.LLM_URL).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 4000
            con.readTimeout = 16000
            con.setRequestProperty("Content-Type", "application/json")
            con.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            val codigo = con.responseCode
            if (codigo != 200) throw IOException("HTTP $codigo")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
    }
}

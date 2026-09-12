package com.mygoll.funform

import com.mygoll.funform.core.Llm
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A rede da resposta aberta (brief 245). Fina de propósito: POST, 200 ou exceção — quem
 * decide o que fazer com o corpo (ou com a falha) é Llm.avaliar, puro e testado.
 *
 * Roda SEMPRE fora da main thread (FunFormService.consultarLlm): a main thread aqui é a
 * do serviço de acessibilidade, e travá-la congela o preenchimento na cara da pessoa.
 * INTERNET só existe na variante debug — no release esta chamada falha na hora e o campo
 * continua aberto, que é a degradação combinada.
 */
object LlmBridge {

    /** Vazia quando quem clonou o repo não tem o local.properties: falha na hora, campo fica aberto. */
    fun temPonte(): Boolean = BuildConfig.LLM_URL.isNotBlank()

    // teto de rede do brief: ≤ 20s no total (conectar 4s + ler 16s)
    fun chamar(corpo: String): String {
        if (!temPonte()) throw IOException("ponte não configurada (local.properties ausente)")
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

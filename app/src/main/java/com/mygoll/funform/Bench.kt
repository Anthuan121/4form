package com.mygoll.funform

import com.mygoll.funform.core.Probe
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sobe o diagnóstico para a sonda de bancada. SÓ NA BUILD DE TESTE (brief avulso 10/09).
 *
 * Duas travas independentes, de propósito:
 *   1. android.permission.INTERNET só existe em app/src/debug/AndroidManifest.xml;
 *   2. o guarda BuildConfig.DEBUG abaixo, que faz o release nem tentar.
 * Uma trava é do sistema de build, a outra do código. Se alguém mover o manifest sem
 * perceber, a segunda ainda segura.
 *
 * O que sobe é o MESMO json do arquivo local, e ele já foi construído para não carregar
 * valor de campo nem nada de campo de senha (Diagnostics.kt, com teste provando). Se
 * carregasse, não subiria: o produto que promete não vazar teria vazado no próprio teste.
 *
 * HttpURLConnection e não uma biblioteca: o projeto tem zero dependência de runtime e este
 * é um PUT de 6 linhas. Thread crua e não corrotina/executor pela mesma razão — é uma
 * chamada solta por varredura, não um pipeline.
 */
object Bench {

    fun enviar(json: String, quandoMs: Long) {
        if (!BuildConfig.DEBUG) return
        // Thread própria: rede na main thread lança NetworkOnMainThreadException, e aqui a
        // main thread é a do serviço de acessibilidade — travá-la trava o preenchimento
        // NA CARA da pessoa, que é a única coisa que a demo não pode fazer.
        Thread {
            runCatching {
                val con = URL(Probe.url(quandoMs)).openConnection() as HttpURLConnection
                con.requestMethod = "PUT"
                con.doOutput = true
                // Curtos porque a sonda é conforto, não requisito: 3G ruim no dia do
                // hackathon não pode virar meio minuto de thread pendurada.
                con.connectTimeout = 4000
                con.readTimeout = 4000
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                con.responseCode // dispara o envio
                con.disconnect()
            }
            // runCatching sem else de propósito: sonda fora do ar, sem rede ou DNS caído
            // NUNCA derruba o preenchimento. Falha silenciosa é o comportamento certo
            // para telemetria — o app é o produto, a sonda é o microscópio.
        }.start()
    }
}

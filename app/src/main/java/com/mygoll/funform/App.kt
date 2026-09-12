package com.mygoll.funform

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A primeira função do app é contar por que ele morreu, sem cabo e sem adb.
 * O handler grava em filesDir/ultimo-crash.txt e DEPOIS repassa ao handler anterior,
 * para o Android seguir com o diálogo padrão de crash.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // migração do 242: o relatório antigo (v0.1/v0.2) gravava VALORES escritos; o
        // diagnóstico novo não grava e o arquivo velho não precisa continuar em disco
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
                // gravar o crash nunca pode causar outro crash
            }
            anterior?.uncaughtException(thread, erro)
        }
    }

    companion object {
        const val ARQUIVO_CRASH = "ultimo-crash.txt"
    }
}

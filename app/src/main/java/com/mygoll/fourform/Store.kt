package com.mygoll.fourform

import com.mygoll.fourform.scan.Learned
import android.content.Context
import com.mygoll.fourform.scan.PathMemory
import com.mygoll.fourform.agent.ItemAberto
import com.mygoll.fourform.agent.ItemPreenchido
import com.mygoll.fourform.agent.Profile
import com.mygoll.fourform.agent.Receipt
import com.mygoll.fourform.scan.Entrada
import com.mygoll.fourform.scan.Fontes
import com.mygoll.fourform.scan.Tsv
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app knows lives in filesDir, in plain text: perfil.txt (pasted by the
 * user), aprendidos.tsv (what it observed), caminhos.tsv (which label level works where),
 * ultimo-recibo.tsv, the diagnosticos/ folder, and ultimo-crash.txt. Nothing leaves the
 * device. Only the diagnostic does, and only via the share button, by the user's own will.
 */
object Store {

    private fun arq(ctx: Context, nome: String) = File(ctx.filesDir, nome)

    // ---- base profile ----

    fun perfilTexto(ctx: Context): String =
        arq(ctx, "perfil.txt").takeIf { it.exists() }?.readText() ?: ""

    fun salvarPerfilTexto(ctx: Context, texto: String) {
        // blank profile = deleted profile; don't leave a ghost empty file on disk
        if (texto.isBlank()) arq(ctx, "perfil.txt").delete() else arq(ctx, "perfil.txt").writeText(texto)
    }

    fun perfil(ctx: Context): Profile = Profile(perfilTexto(ctx), aprendidos(ctx))

    fun apagarPerfil(ctx: Context) = apagarPerfil(ctx.filesDir)

    /**
     * Deletes what is the PERSON'S DATA: base profile, learned entries, and the last
     * receipt (which holds filled-in values). Paths and diagnostics stay: they carry no
     * value at all, proven by a test in 242. Takes a File to be testable in a JVM test,
     * without a Context.
     */
    fun apagarPerfil(dir: File) {
        File(dir, "perfil.txt").delete()
        File(dir, "aprendidos.tsv").delete()
        File(dir, "ultimo-recibo.tsv").delete()
        File(dir, "fontes.tsv").delete()
    }

    // ---- fontes (brief 258: the profile accumulates, so it must say WHERE each pair came from) ----

    fun fontes(ctx: Context): List<Entrada> {
        val f = arq(ctx, "fontes.tsv")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { linha ->
            val p = linha.split('\t')
            if (p.size < 4) return@mapNotNull null
            val quando = p[3].toLongOrNull() ?: return@mapNotNull null
            Entrada(Tsv.des(p[0]), Tsv.des(p[1]), Tsv.des(p[2]), quando)
        }
    }

    private fun salvarFontes(ctx: Context, lista: List<Entrada>) {
        arq(ctx, "fontes.tsv").writeText(
            lista.joinToString("\n") {
                "${Tsv.esc(it.chave)}\t${Tsv.esc(it.valor)}\t${Tsv.esc(it.fonte)}\t${it.quandoMs}"
            }
        )
    }

    /**
     * Appends a confirmed import (or a manual edit) to the log and rebuilds perfil.txt from
     * the winners, key by key. This is the ONLY path that should feed the profile now: it is
     * what makes "import A, then B" additive instead of B erasing A.
     */
    fun adicionarFontes(ctx: Context, novos: List<Entrada>) {
        val atualizado = Fontes.acumular(fontes(ctx), novos)
        salvarFontes(ctx, atualizado)
        salvarPerfilTexto(ctx, Fontes.textoPerfil(atualizado))
    }

    /** Removes a source; keys that also came from another source keep standing on that value. */
    fun removerFonte(ctx: Context, fonte: String) {
        val restante = Fontes.removerFonte(fontes(ctx), fonte)
        salvarFontes(ctx, restante)
        salvarPerfilTexto(ctx, Fontes.textoPerfil(restante))
    }

    // ---- learned entries ----

    fun aprendidos(ctx: Context): List<Learned> {
        val f = arq(ctx, "aprendidos.tsv")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { linha ->
            val p = linha.split('\t')
            if (p.size < 4) return@mapNotNull null
            val quando = p[2].toLongOrNull() ?: return@mapNotNull null
            Learned(Tsv.des(p[0]), Tsv.des(p[1]), quando, Tsv.des(p[3]))
        }
    }

    fun salvarAprendidos(ctx: Context, lista: List<Learned>) {
        arq(ctx, "aprendidos.tsv").writeText(
            lista.joinToString("\n") {
                "${Tsv.esc(it.rotulo)}\t${Tsv.esc(it.valor)}\t${it.quandoMs}\t${Tsv.esc(it.origem)}"
            }
        )
    }

    fun adicionarAprendidos(ctx: Context, novos: List<Learned>) {
        salvarAprendidos(ctx, aprendidos(ctx) + novos)
    }

    fun removerAprendido(ctx: Context, rotulo: String, quandoMs: Long) {
        salvarAprendidos(ctx, aprendidos(ctx).filterNot { it.rotulo == rotulo && it.quandoMs == quandoMs })
    }

    fun editarAprendido(ctx: Context, rotulo: String, quandoMs: Long, novoValor: String) {
        salvarAprendidos(
            ctx,
            aprendidos(ctx).map {
                if (it.rotulo == rotulo && it.quandoMs == quandoMs) it.copy(valor = novoValor) else it
            }
        )
    }

    fun temAprendido(ctx: Context, rotulo: String, quandoMs: Long): Boolean =
        aprendidos(ctx).any { it.rotulo == rotulo && it.quandoMs == quandoMs }

    // ---- receipt ----

    fun gravarRecibo(ctx: Context, r: Receipt) {
        val sb = StringBuilder()
        r.preenchidos.forEach { sb.append("P\t${Tsv.esc(it.rotulo)}\t${Tsv.esc(it.valor)}\t${Tsv.esc(it.fonte)}\n") }
        r.abertos.forEach { sb.append("X\t${Tsv.esc(it.rotulo ?: "?")}\t${Tsv.esc(it.motivo)}\n") }
        r.aprendidos.forEach { sb.append("A\t${Tsv.esc(it.rotulo)}\t${Tsv.esc(it.valor)}\t${it.quandoMs}\t${Tsv.esc(it.origem)}\n") }
        arq(ctx, "ultimo-recibo.tsv").writeText(sb.toString())
    }

    fun lerRecibo(ctx: Context): Receipt? {
        val f = arq(ctx, "ultimo-recibo.tsv")
        if (!f.exists()) return null
        val preenchidos = mutableListOf<ItemPreenchido>()
        val abertos = mutableListOf<ItemAberto>()
        val aprendidos = mutableListOf<Learned>()
        f.readLines().forEach { linha ->
            val p = linha.split('\t')
            when {
                p.size >= 4 && p[0] == "P" -> preenchidos.add(ItemPreenchido(Tsv.des(p[1]), Tsv.des(p[2]), Tsv.des(p[3])))
                p.size >= 3 && p[0] == "X" -> abertos.add(ItemAberto(Tsv.des(p[1]), Tsv.des(p[2])))
                p.size >= 5 && p[0] == "A" -> p[3].toLongOrNull()?.let {
                    aprendidos.add(Learned(Tsv.des(p[1]), Tsv.des(p[2]), it, Tsv.des(p[4])))
                }
            }
        }
        if (preenchidos.isEmpty() && abertos.isEmpty() && aprendidos.isEmpty()) return null
        return Receipt(preenchidos, abertos, aprendidos)
    }

    // ---- diagnostics (brief 242: one per scan, exportable, WITHOUT values) ----

    // ponytail: 20 scans kept; the 21st evicts the oldest one. It's debugging
    // history, not a dead file.
    private const val MAX_DIAGNOSTICOS = 20

    fun pastaDiagnosticos(ctx: Context): File =
        File(ctx.filesDir, "diagnosticos").apply { mkdirs() }

    /**
     * Writes (or rewrites, when the session closes and the final state arrives) the
     * diagnostic. The name is timestamped, so sorting by name = sorting by time.
     */
    fun gravarDiagnostico(ctx: Context, json: String, sobrescrever: File? = null): File {
        val destino = sobrescrever
            ?: File(
                pastaDiagnosticos(ctx),
                "diagnostico-" + SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date()) + ".json",
            )
        destino.writeText(json)
        listarDiagnosticos(ctx).drop(MAX_DIAGNOSTICOS).forEach { it.delete() }
        return destino
    }

    /** Newest to oldest. */
    fun listarDiagnosticos(ctx: Context): List<File> =
        pastaDiagnosticos(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun apagarDiagnosticos(ctx: Context) {
        listarDiagnosticos(ctx).forEach { it.delete() }
    }

    // ---- learned paths (level count per package, and ONLY that) ----

    fun carregarCaminho(ctx: Context): PathMemory =
        arq(ctx, "caminhos.tsv").takeIf { it.exists() }?.let { PathMemory.de(it.readText()) }
            ?: PathMemory.vazio()

    fun salvarCaminho(ctx: Context, caminho: PathMemory) {
        arq(ctx, "caminhos.tsv").writeText(caminho.serializar())
    }

    // ---- crash ----

    fun ultimoCrash(ctx: Context): String? =
        arq(ctx, App.ARQUIVO_CRASH).takeIf { it.exists() }?.readText()
}

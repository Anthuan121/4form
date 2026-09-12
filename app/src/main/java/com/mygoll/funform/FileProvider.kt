package com.mygoll.funform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Serve UM tipo de arquivo, só leitura: os diagnósticos, para o ACTION_SEND (Telegram,
 * e-mail). Caseiro de propósito: o FileProvider oficial mora no androidx e este app não
 * tem dependência de runtime nenhuma — 60 linhas aqui custam menos que a primeira lib.
 * exported=false + grantUriPermissions: só o app alvo do compartilhamento, e só o arquivo
 * concedido no intent, nada mais.
 */
class FileProvider : ContentProvider() {

    companion object {
        const val AUTORIDADE = "com.mygoll.funform.diagnosticos"
        fun uriPara(arquivo: File): Uri =
            Uri.parse("content://$AUTORIDADE/${Uri.encode(arquivo.name)}")
        private val NOME_VALIDO = Regex("diagnostico-[0-9-]+\\.json")
    }

    override fun onCreate(): Boolean = true

    private fun arquivoDe(uri: Uri): File {
        val nome = uri.lastPathSegment ?: throw FileNotFoundException("$uri")
        // trava dupla contra path traversal: nome no formato exato E dentro da pasta
        if (!NOME_VALIDO.matches(nome)) throw FileNotFoundException("$uri")
        val pasta = Store.pastaDiagnosticos(context!!)
        val f = File(pasta, nome)
        if (!f.canonicalPath.startsWith(pasta.canonicalPath + File.separator) || !f.exists()) {
            throw FileNotFoundException("$uri")
        }
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("só leitura")
        return ParcelFileDescriptor.open(arquivoDe(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/json"

    /** Nome e tamanho: é o que Gmail/Telegram consultam antes de anexar. */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val f = arquivoDe(uri)
        val colunas = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(colunas)
        cursor.addRow(
            colunas.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> f.name
                    OpenableColumns.SIZE -> f.length()
                    else -> null
                }
            }
        )
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

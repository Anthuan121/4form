package com.mygoll.fourform

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
 * Serves ONE type of file, read-only: diagnostics, for ACTION_SEND (Telegram,
 * email). Homegrown on purpose: the official FileProvider lives in androidx and this app has
 * zero runtime dependencies. 60 lines here cost less than pulling in the first library.
 * exported=false + grantUriPermissions: only the sharing target app, and only the file
 * granted in the intent, nothing more.
 */
class FileProvider : ContentProvider() {

    companion object {
        const val AUTORIDADE = "com.mygoll.fourform.diagnosticos"
        fun uriPara(arquivo: File): Uri =
            Uri.parse("content://$AUTORIDADE/${Uri.encode(arquivo.name)}")
        private val NOME_VALIDO = Regex("diagnostico-[0-9-]+\\.json")
    }

    override fun onCreate(): Boolean = true

    private fun arquivoDe(uri: Uri): File {
        val nome = uri.lastPathSegment ?: throw FileNotFoundException("$uri")
        // double lock against path traversal: name in the exact format AND inside the folder
        if (!NOME_VALIDO.matches(nome)) throw FileNotFoundException("$uri")
        val pasta = Store.pastaDiagnosticos(context!!)
        val f = File(pasta, nome)
        if (!f.canonicalPath.startsWith(pasta.canonicalPath + File.separator) || !f.exists()) {
            throw FileNotFoundException("$uri")
        }
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(arquivoDe(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/json"

    /** Name and size: what Gmail/Telegram query before attaching. */
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

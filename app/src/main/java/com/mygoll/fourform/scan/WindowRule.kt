package com.mygoll.fourform.scan

/**
 * Decides whether a window change ENDS the fill session.
 * Anthuan's rule (09/09): screen changed = in theory it was submitted; a multi-page form
 * saves on every page. That's why a change WITHIN the same app also ends it.
 * What does NOT end it: the keyboard opening (fires the same event) and the app's own screens.
 */
object WindowRule {

    // ponytail: keyboard detected by package/class name; covers Gboard, Samsung,
    // SwiftKey, and AOSP. An exotic keyboard outside the list would only make the
    // session close too early, and the on-device test measures exactly that.
    private val TECLADOS = listOf("inputmethod", "keyboard", "honeyboard", "swiftkey")

    fun encerraSessao(pacoteEvento: String?, classeEvento: String?, meuPacote: String): Boolean {
        val pacote = pacoteEvento ?: return false
        if (pacote == meuPacote) return false
        if (TECLADOS.any { pacote.contains(it, ignoreCase = true) }) return false
        if (classeEvento?.contains("SoftInput", ignoreCase = true) == true) return false
        return true
    }
}

package com.hiddenoutlier.launcher.assistant

import android.content.Context
import com.hiddenoutlier.launcher.core.Notebook
import com.hiddenoutlier.launcher.platform.SecureFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The assistant's notebook (notes and phrases), encrypted on the phone and never backed up. */
class NotebookStore(context: Context) {
    private val file = SecureFile(context, "notebook.bin", "launcher-notebook")
    private val _book = MutableStateFlow(Notebook.parse(file.read()))
    val book: StateFlow<Notebook> = _book

    /** Saves [nb] unless it's over the size cap; returns whether it was saved. */
    fun save(nb: Notebook): Boolean {
        if (nb.size > Notebook.MAX_CHARS) return false
        _book.value = nb; file.write(nb.toMarkdown()); return true
    }

    /** From the editor: whatever the user typed, trimmed to the cap. */
    fun saveMarkdown(md: String): Notebook {
        val nb = Notebook.parse(md.take(Notebook.MAX_CHARS * 2))
        _book.value = nb; file.write(nb.toMarkdown()); return nb
    }
}

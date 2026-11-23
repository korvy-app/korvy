package com.hiddenoutlier.launcher.assistant

import android.content.Context
import com.hiddenoutlier.launcher.core.AppSearch
import com.hiddenoutlier.launcher.platform.SecureFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "Jen" → this Jennifer, "wife" → that contact. */
data class PersonShortcut(val alias: String, val person: Person)

/**
 * Nicknames and default people for names that match several contacts. Kept only on this phone, encrypted
 * (like the hidden-apps list), excluded from backups, and never shown to the AI model.
 */
class PeopleShortcuts(context: Context) {
    private val file = SecureFile(context, "people.bin", "launcher-people")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<PersonShortcut>> = _all

    private fun load(): List<PersonShortcut> = file.read()?.lineSequence()?.mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4) null else PersonShortcut(f[0], Person(f[1], f[2], f[3], f.getOrNull(4)?.ifBlank { null }))
    }?.toList().orEmpty()

    private fun save(list: List<PersonShortcut>) {
        _all.value = list.sortedBy { it.alias }
        file.write(list.joinToString("\n") { s ->
            listOf(s.alias, s.person.name, s.person.number, s.person.label, s.person.photo.orEmpty()).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        })
    }

    /** The person saved for exactly this name or nickname, if any. */
    fun find(alias: String): Person? {
        val key = AppSearch.normalize(alias)
        return _all.value.firstOrNull { AppSearch.normalize(it.alias) == key }?.person
    }

    fun set(alias: String, person: Person) {
        val key = AppSearch.normalize(alias)
        if (key.isBlank()) return
        save(_all.value.filterNot { AppSearch.normalize(it.alias) == key } + PersonShortcut(alias.trim(), person))
    }

    fun remove(alias: String) = save(_all.value.filterNot { it.alias == alias })
}

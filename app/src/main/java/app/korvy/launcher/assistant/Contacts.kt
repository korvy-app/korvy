package app.korvy.launcher.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import app.korvy.launcher.core.AppSearch

/** One phone number of one contact. */
data class Person(val name: String, val number: String, val label: String, val photo: String? = null)

/**
 * Contact lookup, on the phone only. Used to turn "Mom" into a number for a text or call, and for search.
 * Nothing here is ever given to the model, logged, or sent anywhere; without the permission it returns nothing.
 */
class Contacts(private val context: Context) {
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Phone numbers whose contact name matches [query], best match first (mobile numbers first per person). */
    fun find(query: String, limit: Int = 8): List<Person> {
        if (!granted() || query.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val out = ArrayList<Person>()
        runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 50) {
                    val type = c.getInt(2)
                    out += Person(c.getString(0) ?: continue, c.getString(1) ?: continue,
                        Phone.getTypeLabel(context.resources, type, c.getString(3)).toString(), c.getString(4))
                }
            }
        }
        val byName = out.distinctBy { it.name to it.number.filter(Char::isDigit) }
        return AppSearch.rank(query, byName) { it.name }
            .sortedWith(compareByDescending<Person> { AppSearch.score(query, it.name) }.thenBy { if (it.label.equals("Mobile", true)) 0 else 1 })
            .take(limit)
    }

    /**
     * Everyone who could be meant by [query]: contacts whose nickname (Android's Nickname field) is exactly it,
     * then contacts whose name matches well. One entry per person (their mobile number first).
     */
    fun candidates(query: String): List<Person> {
        if (!granted() || query.isBlank()) return emptyList()
        val nick = byNickname(query)
        val byName = find(query, 20).filter { AppSearch.score(query, it.name) >= 700 }
        return (nick + byName).distinctBy { it.name }.take(6)
    }

    private fun byNickname(query: String): List<Person> = runCatching {
        val ids = ArrayList<Long>()
        context.contentResolver.query(android.provider.ContactsContract.Data.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Data.CONTACT_ID),
            "${android.provider.ContactsContract.Data.MIMETYPE} = ? AND ${android.provider.ContactsContract.CommonDataKinds.Nickname.NAME} = ? COLLATE NOCASE",
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, query.trim()), null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        ids.distinct().flatMap { id ->
            val out = ArrayList<Person>()
            context.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI),
                "${Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
                while (c.moveToNext()) out += Person(c.getString(0), c.getString(1),
                    Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString(), c.getString(4))
            }
            out.sortedBy { if (it.label.equals("Mobile", true)) 0 else 1 }.take(1)
        }
    }.getOrDefault(emptyList())

    /** The best single match, or null if nothing matches well enough. */
    fun best(query: String): Person? = find(query, 1).firstOrNull()?.takeIf { AppSearch.score(query, it.name) >= 700 }

    fun nameExists(query: String) = best(query) != null

    /** Reads the contact the user picked in Android's contact picker (works without the contacts permission). */
    fun fromPicked(uri: Uri): Person? = runCatching {
        context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI), null, null, null)?.use { c ->
            if (c.moveToFirst()) Person(c.getString(0), c.getString(1), Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString(), c.getString(4)) else null
        }
    }.getOrNull()
}

/** A contact's thumbnail, or null. Reads only that one picture, on the calling (background) thread. */
fun Context.contactPhoto(uri: String?): android.graphics.Bitmap? = uri?.let {
    runCatching { contentResolver.openInputStream(Uri.parse(it))?.use { s -> android.graphics.BitmapFactory.decodeStream(s) } }.getOrNull()
}

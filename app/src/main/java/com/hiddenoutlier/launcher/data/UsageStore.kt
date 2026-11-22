package com.hiddenoutlier.launcher.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hiddenoutlier.launcher.core.Launch
import com.hiddenoutlier.launcher.core.Suggest

/**
 * Local history of launches made from this launcher, for time-of-day suggestions. Plain SQLite (no extra
 * library, fast to open). Lives in the app's private storage and is excluded from backups.
 */
class UsageStore(context: Context) : SQLiteOpenHelper(context, "usage.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE launches (app TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX launches_ts ON launches(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(key: String, now: Long = System.currentTimeMillis()) {
        writableDatabase.insert("launches", null, ContentValues().apply { put("app", key); put("ts", now) })
    }

    fun recent(now: Long = System.currentTimeMillis()): List<Launch> {
        val since = now - Suggest.MAX_AGE_DAYS * 86_400_000L
        writableDatabase.delete("launches", "ts < ?", arrayOf(since.toString()))
        val out = ArrayList<Launch>()
        readableDatabase.rawQuery("SELECT app, ts FROM launches WHERE ts >= ?", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out += Launch(c.getString(0), c.getLong(1))
        }
        return out
    }

    fun forget(key: String) {
        writableDatabase.delete("launches", "app = ?", arrayOf(key))
    }

    fun forgetAll() {
        writableDatabase.delete("launches", null, null)
    }
}

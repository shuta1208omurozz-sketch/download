package jp.example.floatsaver

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryItem(
    val id: Long,
    val title: String,
    val source: String,
    val format: String,
    val state: String,
    val bytes: Long?,
    val fileName: String?,
    val outputUri: String?,
    val error: String?,
    val createdAt: Long
)

class HistoryStore(context: Context) : SQLiteOpenHelper(context, "floatsaver_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE downloads (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL, source TEXT NOT NULL, format TEXT NOT NULL,
            state TEXT NOT NULL, bytes INTEGER, filename TEXT, output_uri TEXT,
            error TEXT, created_at INTEGER NOT NULL)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun begin(title: String, url: String, format: String): Long =
        writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
            put("title", title.take(180))
            put("source", url)
            put("format", format)
            put("state", "保存中")
            put("created_at", System.currentTimeMillis())
        })

    fun succeed(id: Long, result: SaveResult) = update(id, ContentValues().apply {
        put("state", "完了")
        put("bytes", result.bytes)
        put("filename", result.fileName)
        put("output_uri", result.uri.toString())
        putNull("error")
    })

    fun fail(id: Long, reason: String) = update(id, ContentValues().apply {
        put("state", "失敗")
        put("error", reason.take(300))
    })

    private fun update(id: Long, values: ContentValues) {
        writableDatabase.update("downloads", values, "id=?", arrayOf(id.toString()))
    }

    fun all(): List<HistoryItem> {
        val results = mutableListOf<HistoryItem>()
        readableDatabase.query("downloads", null, null, null, null, null,
            "created_at DESC, id DESC", "100").use { cursor ->
            fun str(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key))
            while (cursor.moveToNext()) {
                val bytesIndex = cursor.getColumnIndexOrThrow("bytes")
                results.add(HistoryItem(
                    cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    str("title"), str("source"), str("format"), str("state"),
                    if (cursor.isNull(bytesIndex)) null else cursor.getLong(bytesIndex),
                    str("filename"), str("output_uri"), str("error"),
                    cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                ))
            }
        }
        return results
    }

    fun markInterrupted() {
        writableDatabase.update("downloads", ContentValues().apply {
            put("state", "中断")
            put("error", "アプリまたはAndroidが終了したため、中断されました。再度解析して保存できます。")
        }, "state=?", arrayOf("保存中"))
    }

    fun clearHistory() { writableDatabase.delete("downloads", null, null) }
}

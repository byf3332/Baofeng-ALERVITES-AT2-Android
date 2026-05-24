package com.byf3332.at2ht

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Base64

class OfflineChatStore(context: Context) : SQLiteOpenHelper(context, "offline_chat.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_chat_messages (
                thread_key TEXT NOT NULL,
                id INTEGER NOT NULL,
                protocol_id INTEGER,
                self INTEGER NOT NULL,
                sender TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                image_data BLOB,
                voice_data BLOB,
                voice_duration_ms INTEGER,
                voice_unread INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(thread_key, id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_offline_chat_thread_id
            ON offline_chat_messages(thread_key, id)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE offline_chat_messages ADD COLUMN image_data BLOB")
        }
    }

    fun loadThread(threadKey: String, limit: Int? = null): List<OfflineChatMessage> {
        val result = mutableListOf<OfflineChatMessage>()
        val orderBy = if (limit != null) "id DESC LIMIT $limit" else "id ASC"
        readableDatabase.query(
            "offline_chat_messages",
            arrayOf(
                "id",
                "protocol_id",
                "self",
                "sender",
                "kind",
                "title",
                "body",
                "image_data",
                "voice_data",
                "voice_duration_ms",
                "voice_unread"
            ),
            "thread_key=?",
            arrayOf(threadKey),
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val protocolIndex = cursor.getColumnIndexOrThrow("protocol_id")
                val voiceDurationIndex = cursor.getColumnIndexOrThrow("voice_duration_ms")
                val imageBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("image_data"))
                val voiceBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("voice_data"))
                result.add(
                    OfflineChatMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        protocolId = if (cursor.isNull(protocolIndex)) null else cursor.getLong(protocolIndex),
                        self = cursor.getInt(cursor.getColumnIndexOrThrow("self")) != 0,
                        sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                        kind = runCatching {
                            ChatMessageKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("kind")))
                        }.getOrDefault(ChatMessageKind.Text),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        imageData = imageBlob,
                        voiceData = voiceBlob,
                        voiceDurationMs = if (cursor.isNull(voiceDurationIndex)) null else cursor.getInt(voiceDurationIndex),
                        voiceUnread = cursor.getInt(cursor.getColumnIndexOrThrow("voice_unread")) != 0,
                        pending = false,
                    )
                )
            }
        }
        if (limit != null) result.reverse()
        return result
    }

    fun loadThreadBefore(threadKey: String, beforeId: Long, limit: Int): List<OfflineChatMessage> {
        val result = mutableListOf<OfflineChatMessage>()
        readableDatabase.query(
            "offline_chat_messages",
            arrayOf(
                "id",
                "protocol_id",
                "self",
                "sender",
                "kind",
                "title",
                "body",
                "image_data",
                "voice_data",
                "voice_duration_ms",
                "voice_unread"
            ),
            "thread_key=? AND id<?",
            arrayOf(threadKey, beforeId.toString()),
            null,
            null,
            "id DESC LIMIT $limit"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val protocolIndex = cursor.getColumnIndexOrThrow("protocol_id")
                val voiceDurationIndex = cursor.getColumnIndexOrThrow("voice_duration_ms")
                result.add(
                    OfflineChatMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        protocolId = if (cursor.isNull(protocolIndex)) null else cursor.getLong(protocolIndex),
                        self = cursor.getInt(cursor.getColumnIndexOrThrow("self")) != 0,
                        sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                        kind = runCatching {
                            ChatMessageKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("kind")))
                        }.getOrDefault(ChatMessageKind.Text),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
                        imageData = cursor.getBlob(cursor.getColumnIndexOrThrow("image_data")),
                        voiceData = cursor.getBlob(cursor.getColumnIndexOrThrow("voice_data")),
                        voiceDurationMs = if (cursor.isNull(voiceDurationIndex)) null else cursor.getInt(voiceDurationIndex),
                        voiceUnread = cursor.getInt(cursor.getColumnIndexOrThrow("voice_unread")) != 0,
                        pending = false,
                    )
                )
            }
        }
        result.reverse()
        return result
    }

    fun hasThreadBefore(threadKey: String, beforeId: Long): Boolean =
        readableDatabase.query(
            "offline_chat_messages",
            arrayOf("id"),
            "thread_key=? AND id<?",
            arrayOf(threadKey, beforeId.toString()),
            null,
            null,
            "id DESC LIMIT 1"
        ).use { it.moveToFirst() }

    fun upsertMessage(threadKey: String, item: OfflineChatMessage) {
        writableDatabase.insertWithOnConflict(
            "offline_chat_messages",
            null,
            ContentValues().apply {
                put("thread_key", threadKey)
                put("id", item.id)
                item.protocolId?.let { put("protocol_id", it) } ?: putNull("protocol_id")
                put("self", if (item.self) 1 else 0)
                put("sender", item.sender)
                put("kind", item.kind.name)
                put("title", item.title)
                put("body", item.body)
                item.imageData?.let {
                    put("image_data", it)
                } ?: item.imageBase64?.let {
                    put("image_data", runCatching { Base64.getDecoder().decode(it) }.getOrNull())
                } ?: putNull("image_data")
                item.voiceData?.let {
                    put("voice_data", it)
                } ?: item.voiceBase64?.let {
                    put("voice_data", runCatching { Base64.getDecoder().decode(it) }.getOrNull())
                } ?: putNull("voice_data")
                item.voiceDurationMs?.let { put("voice_duration_ms", it) } ?: putNull("voice_duration_ms")
                put("voice_unread", if (item.voiceUnread) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteMessage(threadKey: String, id: Long) {
        writableDatabase.delete(
            "offline_chat_messages",
            "thread_key=? AND id=?",
            arrayOf(threadKey, id.toString())
        )
    }

    fun clearThread(threadKey: String) {
        writableDatabase.delete("offline_chat_messages", "thread_key=?", arrayOf(threadKey))
    }

    fun trimThreadToLatest(threadKey: String, keepLatest: Int) {
        writableDatabase.delete(
            "offline_chat_messages",
            "thread_key=? AND id NOT IN (SELECT id FROM offline_chat_messages WHERE thread_key=? ORDER BY id DESC LIMIT $keepLatest)",
            arrayOf(threadKey, threadKey)
        )
    }
}

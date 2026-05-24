package com.byf3332.at2ht

import android.content.Context

object AppStrings {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun chatSenderDefault(): String = appContext.getString(R.string.chat_sender_default)

    fun imageReadContentFailed(): String = appContext.getString(R.string.image_read_content_failed)
}

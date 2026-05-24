package com.byf3332.at2ht

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.collection.LruCache
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.Base64

class OfflineChatAdapter(
    private val onVoiceClick: (OfflineChatMessage) -> Unit,
    private val onImageClick: (OfflineChatMessage) -> Unit,
    private val isVoicePlaying: (Long) -> Boolean,
    private val isVoicePlayingAlternate: (Long) -> Boolean,
) : RecyclerView.Adapter<OfflineChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<OfflineChatMessage>()
    private val imageBitmapCache = object : LruCache<String, android.graphics.Bitmap>((Runtime.getRuntime().maxMemory() / 1024L / 16L).coerceAtLeast(1024L).toInt()) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount / 1024
    }

    fun submit(newItems: List<OfflineChatMessage>) {
        messages.clear()
        messages.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view, onVoiceClick, onImageClick, isVoicePlaying, isVoicePlayingAlternate, imageBitmapCache)
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun onViewRecycled(holder: ChatViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    class ChatViewHolder(
        view: View,
        private val onVoiceClick: (OfflineChatMessage) -> Unit,
        private val onImageClick: (OfflineChatMessage) -> Unit,
        private val isVoicePlaying: (Long) -> Boolean,
        private val isVoicePlayingAlternate: (Long) -> Boolean,
        private val imageBitmapCache: LruCache<String, android.graphics.Bitmap>,
    ) : RecyclerView.ViewHolder(view) {
        private val leftGroup: LinearLayout = view.findViewById(R.id.leftGroup)
        private val rightGroup: LinearLayout = view.findViewById(R.id.rightGroup)
        private val tvLeftAvatar: TextView = view.findViewById(R.id.tvLeftAvatar)
        private val tvLeftSender: TextView = view.findViewById(R.id.tvLeftSender)
        private val leftBubbleContainer: FrameLayout = view.findViewById(R.id.leftBubbleContainer)
        private val leftPendingContainer: FrameLayout = view.findViewById(R.id.leftPendingContainer)
        private val leftBubble: LinearLayout = view.findViewById(R.id.leftBubble)
        private val tvLeftTitle: TextView = view.findViewById(R.id.tvLeftTitle)
        private val tvLeftBody: TextView = view.findViewById(R.id.tvLeftBody)
        private val viewLeftImage: ImageView = view.findViewById(R.id.viewLeftImage)
        private val progressLeftPending: CircularProgressIndicator = view.findViewById(R.id.progressLeftPending)
        private val leftUnreadDot: View = view.findViewById(R.id.leftUnreadDot)
        private val tvRightAvatar: TextView = view.findViewById(R.id.tvRightAvatar)
        private val rightBubble: LinearLayout = view.findViewById(R.id.rightBubble)
        private val tvRightTitle: TextView = view.findViewById(R.id.tvRightTitle)
        private val tvRightBody: TextView = view.findViewById(R.id.tvRightBody)
        private val viewRightImage: ImageView = view.findViewById(R.id.viewRightImage)
        private val rightPendingContainer: FrameLayout = view.findViewById(R.id.rightPendingContainer)
        private val progressRightPending: CircularProgressIndicator = view.findViewById(R.id.progressRightPending)
        private val tvRightPendingPercent: TextView = view.findViewById(R.id.tvRightPendingPercent)

        fun bind(message: OfflineChatMessage) {
            leftGroup.isVisible = !message.self
            rightGroup.isVisible = message.self
            val voicePlaying = message.kind == ChatMessageKind.Voice && isVoicePlaying(message.id)
            val voiceAlternate = message.kind == ChatMessageKind.Voice && isVoicePlayingAlternate(message.id)
            if (message.self) {
                tvRightAvatar.text = itemView.context.getString(R.string.chat_self_label)
                rightPendingContainer.isVisible = message.pending
                progressRightPending.progress = message.pendingProgress.coerceIn(0, 100)
                tvRightPendingPercent.text = "${message.pendingProgress.coerceIn(0, 100)}%"
                rightBubble.isVisible = true
                leftUnreadDot.isVisible = false
                bindBubble(rightBubble, tvRightTitle, tvRightBody, viewRightImage, message, incoming = false, voicePlaying = voicePlaying, voiceAlternate = voiceAlternate)
            } else {
                tvLeftAvatar.text = message.sender.take(1)
                tvLeftSender.text = message.sender
                leftPendingContainer.isVisible = message.pending
                progressLeftPending.progress = message.pendingProgress.coerceIn(0, 100)
                configureIncomingPendingIndicator(message)
                leftBubble.isVisible = !message.pending || message.imageBase64 != null
                leftUnreadDot.isVisible = !message.pending && message.kind == ChatMessageKind.Voice && message.voiceUnread
                bindBubble(leftBubble, tvLeftTitle, tvLeftBody, viewLeftImage, message, incoming = true, voicePlaying = voicePlaying, voiceAlternate = voiceAlternate)
            }
        }

        private fun configureIncomingPendingIndicator(message: OfflineChatMessage) {
            val params = leftPendingContainer.layoutParams as? LinearLayout.LayoutParams ?: return
            val previewVisible = message.pending && message.kind == ChatMessageKind.Image && message.imageBase64 != null
            if (previewVisible) {
                leftBubbleContainer.setPadding(
                    dp(progressLeftPending, 16),
                    0,
                    0,
                    dp(progressLeftPending, 16)
                )
                params.gravity = Gravity.BOTTOM
                params.marginStart = -dp(progressLeftPending, -5)
                params.marginEnd = 0
                params.bottomMargin = dp(progressLeftPending, 20)
            } else {
                leftBubbleContainer.setPadding(
                    dp(progressLeftPending, 16),
                    0,
                    0,
                    dp(progressLeftPending, 16)
                )
                params.gravity = Gravity.BOTTOM
                params.marginStart = 0
                params.marginEnd = 0
                params.bottomMargin = 0
            }
            leftPendingContainer.layoutParams = params
        }

        private fun bindBubble(
            bubbleView: LinearLayout,
            titleView: TextView,
            bodyView: TextView,
            imageView: ImageView,
            message: OfflineChatMessage,
            incoming: Boolean,
            voicePlaying: Boolean,
            voiceAlternate: Boolean,
        ) {
            bubbleView.setOnClickListener(null)
            bubbleView.isClickable = false
            imageView.setOnClickListener(null)
            imageView.isClickable = false
            titleView.setOnClickListener(null)
            titleView.isClickable = false
            titleView.setTextIsSelectable(false)
            bodyView.setTextIsSelectable(false)
            titleView.paint.isFakeBoldText = false
            bodyView.paint.isFakeBoldText = false
            titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            titleView.compoundDrawablePadding = 0
            titleView.maxLines = Int.MAX_VALUE
            if (message.pending) {
                val pendingImage = decodeImageBitmap(message)
                if (incoming) {
                    titleView.isVisible = pendingImage == null
                    titleView.text = if (pendingImage == null) {
                        itemView.context.getString(R.string.chat_receiving_progress, message.pendingProgress.coerceIn(0, 100))
                    } else ""
                    titleView.setTextColor(0xFF111827.toInt())
                    titleView.setTypeface(null, Typeface.NORMAL)
                    imageView.isVisible = pendingImage != null
                    imageView.setImageBitmap(pendingImage)
                    bodyView.isVisible = false
                    bodyView.text = ""
                } else {
                    titleView.isVisible = pendingImage == null
                    titleView.text = message.title
                    titleView.setTextColor(0xFF111827.toInt())
                    titleView.setTypeface(null, Typeface.NORMAL)
                    imageView.isVisible = pendingImage != null
                    imageView.setImageBitmap(pendingImage)
                    bodyView.isVisible = false
                }
                return
            }
            when (message.kind) {
                ChatMessageKind.Text -> {
                    titleView.isVisible = true
                    titleView.text = message.title
                    if (message.title == itemView.context.getString(R.string.common_error_short)) {
                        titleView.setTextColor(0xFFD11A2A.toInt())
                        titleView.setTypeface(null, Typeface.BOLD_ITALIC)
                    } else {
                        titleView.setTextColor(0xFF111827.toInt())
                        titleView.setTypeface(null, Typeface.NORMAL)
                    }
                    titleView.textSize = 16f
                    titleView.setTextIsSelectable(true)
                    imageView.isVisible = false
                    imageView.setImageDrawable(null)
                    bodyView.isVisible = false
                    bodyView.setTextColor(0xFF2563EB.toInt())
                    clearMessageLeadingIcon(titleView)
                }

                ChatMessageKind.Location -> {
                    titleView.isVisible = true
                    titleView.text = replaceLeadingIcon(
                        titleView,
                        message.title,
                        R.drawable.ic_chat_location_navigation
                    )
                    titleView.setTextColor(0xFF111827.toInt())
                    titleView.setTypeface(null, Typeface.BOLD)
                    titleView.textSize = 16f
                    titleView.setTextIsSelectable(true)
                    imageView.isVisible = false
                    imageView.setImageDrawable(null)
                    bodyView.isVisible = true
                    bodyView.text = message.body
                    bodyView.setTextColor(0xFF2563EB.toInt())
                    bodyView.setTextIsSelectable(true)
                    clearMessageLeadingIcon(titleView)
                }

                ChatMessageKind.Help -> {
                    titleView.isVisible = true
                    titleView.text = replaceLeadingIcon(
                        titleView,
                        message.title,
                        R.drawable.ic_chat_help_alarm
                    )
                    titleView.setTextColor(0xFFD11A2A.toInt())
                    titleView.setTypeface(null, Typeface.BOLD)
                    titleView.paint.isFakeBoldText = true
                    titleView.textSize = 16f
                    titleView.setTextIsSelectable(true)
                    imageView.isVisible = false
                    imageView.setImageDrawable(null)
                    bodyView.isVisible = true
                    bodyView.text = message.body
                    bodyView.setTextColor(0xFF2563EB.toInt())
                    bodyView.paint.isFakeBoldText = false
                    bodyView.setTextIsSelectable(true)
                    clearMessageLeadingIcon(titleView)
                }

                ChatMessageKind.Image -> {
                    titleView.isVisible = message.title.isNotBlank()
                    titleView.text = message.title
                    titleView.setTextColor(0xFF111827.toInt())
                    titleView.setTypeface(null, Typeface.BOLD)
                    titleView.textSize = 16f
                    titleView.setTextIsSelectable(true)
                    imageView.isVisible = true
                    imageView.setImageBitmap(decodeImageBitmap(message))
                    imageView.isClickable = message.imageData != null || message.imageBase64 != null
                    imageView.setOnClickListener { onImageClick(message) }
                    bodyView.isVisible = message.body.isNotBlank()
                    bodyView.text = message.body
                    bodyView.setTextColor(0xFF2563EB.toInt())
                    bodyView.setTextIsSelectable(message.body.isNotBlank())
                    clearMessageLeadingIcon(titleView)
                }

                ChatMessageKind.Voice -> {
                    titleView.isVisible = true
                    titleView.text = message.title
                    titleView.setTextColor(0xFF111827.toInt())
                    titleView.setTypeface(null, Typeface.NORMAL)
                    imageView.isVisible = false
                    imageView.setImageDrawable(null)
                    bodyView.isVisible = false
                    bodyView.text = ""
                    titleView.textSize = 16f
                    titleView.maxLines = 1
                    bindVoiceIcon(titleView, incoming, voicePlaying, voiceAlternate)
                    val clickable = message.voiceData != null || message.voiceBase64 != null
                    bubbleView.isClickable = clickable
                    bubbleView.setOnClickListener { onVoiceClick(message) }
                    titleView.isClickable = clickable
                    titleView.setOnClickListener { onVoiceClick(message) }
                }
            }
        }

        fun recycle() {
            viewLeftImage.setImageDrawable(null)
            viewRightImage.setImageDrawable(null)
            tvLeftTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            tvRightTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }

        private fun clearMessageLeadingIcon(titleView: TextView) {
            titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }

        private fun replaceLeadingIcon(
            titleView: TextView,
            text: CharSequence,
            @DrawableRes iconRes: Int,
        ): CharSequence {
            val raw = text.toString()
            val icon = AppCompatResources.getDrawable(titleView.context, iconRes) ?: return raw
            val iconSize = dp(titleView, 18)
            icon.setBounds(0, 0, iconSize, iconSize)
            val label = raw.trimStart()
            return SpannableStringBuilder("  $label").apply {
                setSpan(ImageSpan(icon, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        private fun bindVoiceIcon(
            titleView: TextView,
            incoming: Boolean,
            voicePlaying: Boolean,
            voiceAlternate: Boolean,
        ) {
            @DrawableRes val iconRes = when {
                incoming && voicePlaying && voiceAlternate -> R.drawable.ic_voice_speaker_no_dots
                incoming -> R.drawable.ic_voice_speaker
                voicePlaying && voiceAlternate -> R.drawable.ic_voice_speaker_no_dots_left
                else -> R.drawable.ic_voice_speaker_left
            }
            val icon = AppCompatResources.getDrawable(titleView.context, iconRes)
            titleView.compoundDrawablePadding = dp(titleView, 8)
            if (incoming) {
                titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            } else {
                titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
            }
        }

        private fun decodeImageBitmap(message: OfflineChatMessage): android.graphics.Bitmap? {
            val cacheKey = when {
                message.imageBase64 != null -> "b64:${message.imageBase64.hashCode()}"
                message.imageData != null -> "bin:${message.id}:${message.imageData.contentHashCode()}"
                else -> return null
            }
            imageBitmapCache.get(cacheKey)?.let { return it }
            val bytes = message.imageData ?: runCatching { Base64.getDecoder().decode(message.imageBase64) }.getOrNull() ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            imageBitmapCache.put(cacheKey, bitmap)
            return bitmap
        }

        private fun dp(view: View, value: Int): Int =
            (value * view.resources.displayMetrics.density).toInt()
    }
}

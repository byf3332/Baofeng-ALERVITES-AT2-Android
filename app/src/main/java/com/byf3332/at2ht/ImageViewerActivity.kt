package com.byf3332.at2ht

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import com.byf3332.at2ht.widget.ZoomableImageView
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ImageViewerActivity : ComponentActivity() {
    private var imageKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.dialog_image_viewer)

        imageKey = intent.getStringExtra(EXTRA_IMAGE_KEY)
        val bitmap = imageKey?.let { key ->
            imageMemory.remove(key)?.let { bytes ->
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
        }
        if (bitmap == null) {
            finish()
            return
        }

        findViewById<ZoomableImageView>(R.id.viewFullscreenImage)?.setImageBitmap(bitmap)
        findViewById<TextView>(R.id.btnCloseFullscreenImage)?.setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSaveFullscreenImage)?.setOnClickListener {
            val ok = saveBitmapToGallery(bitmap)
            Toast.makeText(
                this,
                if (ok) getString(R.string.chat_saved_album_success) else getString(R.string.chat_saved_album_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        imageKey?.let { key -> imageMemory.remove(key) }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "AT2HT_${System.currentTimeMillis()}.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "AT2HT")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            contentResolver.openOutputStream(uri)?.use { output: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    error("compress failed")
                }
            } ?: error("openOutputStream failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            true
        }.getOrElse {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    companion object {
        private const val EXTRA_IMAGE_KEY = "extra_image_key"
        private val imageMemory = ConcurrentHashMap<String, ByteArray>()

        fun createIntent(context: Context, bytes: ByteArray): Intent {
            val key = UUID.randomUUID().toString()
            imageMemory[key] = bytes
            return Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_IMAGE_KEY, key)
        }
    }
}

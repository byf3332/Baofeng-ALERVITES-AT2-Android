package com.byf3332.at2ht.core.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.byf3332.at2ht.AppStrings
import com.byf3332.at2ht.core.protocol.At2OfflineMessageCodec
import resizer.Resizer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val OFFICIAL_RESIZE_MAX_SIZE = 300L
private const val OFFICIAL_RESIZE_QUALITY = 75L
private const val OFFICIAL_ROTATE_JPEG_QUALITY = 100

data class OfflineImagePayload(
    val jpegBytes: ByteArray,
    val originalWidth: Int,
    val originalHeight: Int,
)

object OfflineImageProcessor {
    fun prepare(contentResolver: ContentResolver, uri: Uri): OfflineImagePayload? {
        val sourceBytes = readSourceBytes(contentResolver, uri) ?: error(AppStrings.imageReadContentFailed())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ByteArrayInputStream(sourceBytes).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = ByteArrayInputStream(sourceBytes).use { input ->
            runCatching {
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }

        val sourceSize = rotateSize(bounds.outWidth, bounds.outHeight, orientation)
        val sourceWidth = sourceSize.first
        val sourceHeight = sourceSize.second
        val officialInput = rewriteLikeOfficial(sourceBytes, orientation) ?: return null
        val resizedBytes = Resizer.resizeImage(officialInput, OFFICIAL_RESIZE_MAX_SIZE, OFFICIAL_RESIZE_QUALITY)
        return if (resizedBytes.isEmpty()) null else OfflineImagePayload(resizedBytes, sourceWidth, sourceHeight)
    }

    private fun rewriteLikeOfficial(sourceBytes: ByteArray, orientation: Int): ByteArray? {
        val tempFile = File.createTempFile("offline_image_", ".jpg")
        return try {
            tempFile.writeBytes(sourceBytes)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(tempFile.absolutePath, options) ?: return null
            val rotated = applyOfficialOrientation(decoded, orientation)
            FileOutputStream(tempFile).use { output ->
                rotated.compress(Bitmap.CompressFormat.JPEG, OFFICIAL_ROTATE_JPEG_QUALITY, output)
                output.flush()
            }
            if (rotated !== decoded) {
                rotated.recycle()
            }
            decoded.recycle()
            tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun readSourceBytes(contentResolver: ContentResolver, uri: Uri): ByteArray? {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            return descriptor.fileDescriptor.useFileBytes()
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        }
    }

    private fun java.io.FileDescriptor.useFileBytes(): ByteArray {
        return java.io.FileInputStream(this).use { input ->
            input.readBytes()
        }
    }

    private fun rotateSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> height to width
            else -> width to height
        }
    }

    private fun applyOfficialOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

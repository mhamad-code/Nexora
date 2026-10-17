package com.mhamad.nexora.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.mhamad.nexora.utils.ImageUtils
import java.io.File
import kotlin.uuid.Uuid

/**
 */
object MediaCreationFiles {
    data class MediaInfo(
        val width: Int,
        val height: Int,
        val durationSeconds: Double? = null,
    )

    /**
     */
    fun importImage(context: Context, uri: Uri, dir: File): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIZE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("تعذر قراءة الصورة")
        val bitmap = ImageUtils.correctImageOrientation(context, uri, decoded)
        try {
            val (format, extension) = if (bitmap.hasAlpha()) {
                Bitmap.CompressFormat.PNG to "png"
            } else {
                Bitmap.CompressFormat.JPEG to "jpg"
            }
            dir.mkdirs()
            return File(dir, "${Uuid.random()}.$extension").also { file ->
                file.outputStream().use { bitmap.compress(format, JPEG_QUALITY, it) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun importVideo(context: Context, uri: Uri, dir: File): File {
        val extension = when (context.contentResolver.getType(uri)?.lowercase()) {
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            else -> "mp4"
        }
        dir.mkdirs()
        val file = File(dir, "${Uuid.random()}.$extension")
        val input = context.contentResolver.openInputStream(uri) ?: error("تعذر قراءة الفيديو")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    fun imageInfo(file: File): MediaInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return MediaInfo(width = bounds.outWidth, height = bounds.outHeight)
    }

    fun videoInfo(file: File): MediaInfo? = file.withRetriever { retriever ->
        val width = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: return@withRetriever null
        val height = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: return@withRetriever null
        val rotated = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) in listOf(90, 270)
        MediaInfo(
            width = if (rotated) height else width,
            height = if (rotated) width else height,
            durationSeconds = retriever.metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let { it / 1000.0 },
        )
    }

    /**
     */
    fun extractFrame(video: File, target: File, last: Boolean): Boolean = video.withRetriever { retriever ->
        val bitmap = if (last) retriever.lastFrame() else retriever.getFrameAtTime(0)
        bitmap ?: return@withRetriever false
        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    } ?: false

    fun videoThumbnail(file: File, size: Int = 256): Bitmap? = file.withRetriever { retriever ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
        } else {
            retriever.getFrameAtTime(0)
        }
    }

    private fun MediaMetadataRetriever.lastFrame(): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val frameCount = metadataInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            if (frameCount != null && frameCount > 0) {
                runCatching { getFrameAtIndex(frameCount - 1) }.getOrNull()?.let { return it }
            }
        }
        val durationUs = (metadataInt(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return null) * 1000L
        return getFrameAtTime(durationUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: getFrameAtTime(durationUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
    }

    private fun MediaMetadataRetriever.metadataInt(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

    private fun <T> File.withRetriever(block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(absolutePath)
            block(retriever)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    const val PARTIAL_SUFFIX = ".part"

    private const val MAX_IMAGE_SIZE = 4096
    private const val JPEG_QUALITY = 95
}

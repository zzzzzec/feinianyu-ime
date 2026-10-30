// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import android.net.Uri
import android.webkit.MimeTypeMap
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class ClipboardImageStore(private val context: Context, private val directory: File = File(context.filesDir, "clipboard-images")) {
    data class Stored(val file: String, val mime: String)
    class Opened(val descriptor: AssetFileDescriptor) : Closeable {
        override fun close() = descriptor.close()
    }
    class TooLarge : IOException("Clipboard image exceeds 32 MiB")
    private val thumbnails = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    companion object {
        const val MAX_BYTES = 32 * 1024 * 1024
        private val validName = Regex("[a-f0-9]{64}\\.(png|jpg|webp|gif|bmp|heic|avif)")
        private val extensions = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/x-ms-bmp" to "bmp",
            "image/gif" to "gif", "image/bmp" to "bmp", "image/heif" to "heic", "image/heic" to "heic", "image/avif" to "avif")
    }

    fun ownedName(uri: Uri?): String? = uri?.takeIf {
        it.authority == "${context.packageName}.clipboard" && it.pathSegments.size == 2 && it.pathSegments.first() == "images"
    }?.lastPathSegment?.takeIf(validName::matches)

    // Open while the system clipboard grant is still current. The open file
    // descriptor remains readable if another app replaces the primary clip.
    fun open(clip: ClipData): Opened? {
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        if (uri.scheme != "content") return null
        val mime = context.contentResolver.getType(uri)
        if (mime?.startsWith("image/") != true && !clip.description.hasMimeType("image/*")) return null
        return Opened(context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Image unavailable"))
    }

    fun save(input: InputStream): Stored {
        check(directory.isDirectory || directory.mkdirs()) { "Image directory unavailable" }
        val temporary = File(directory, ".import-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > MAX_BYTES) throw TooLarge()
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            val extension = extensions[bounds.outMimeType] ?: throw IOException("Unsupported image")
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "Invalid image dimensions" }
            val name = digest.digest().joinToString("") { "%02x".format(it) } + ".$extension"
            val destination = file(name)
            if (!destination.exists()) check(temporary.renameTo(destination)) { "Cannot save image" }
            return Stored(name, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: bounds.outMimeType)
        } finally { temporary.delete() }
    }

    fun file(name: String): File {
        require(validName.matches(name)) { "Invalid clipboard image name" }
        return File(directory, name).also { require(it.canonicalFile.parentFile == directory.canonicalFile) }
    }

    fun uri(name: String): Uri {
        val image = file(name)
        check(image.isFile) { "Clipboard image is missing" }
        return FileProvider.getUriForFile(context, "${context.packageName}.clipboard", image)
    }

    suspend fun thumbnail(name: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(name)?.let { return@withContext it }
        decode(name, 768, 2048, 1_048_576)?.also { thumbnails.put(name, it) }
    }

    suspend fun preview(name: String, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        decode(name, width.coerceIn(256, 1536), 8192, 4_194_304)
    }

    private fun decode(name: String, widthLimit: Int, heightLimit: Int, pixelLimit: Int): Bitmap? {
        val path = file(name).absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > widthLimit || bounds.outHeight / inSampleSize > heightLimit ||
                (bounds.outWidth / inSampleSize).toLong() * (bounds.outHeight / inSampleSize) > pixelLimit) inSampleSize *= 2
        }
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, _, _ ->
                decoder.setTargetSampleSize(options.inSampleSize)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else BitmapFactory.decodeFile(path, options)?.let { bitmap ->
            val matrix = Matrix()
            when (runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                6 -> matrix.setRotate(90f)
                7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                8 -> matrix.setRotate(-90f)
            }
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    fun cleanUp(retained: Set<String>) {
        directory.listFiles()?.forEach { file ->
            if (!file.isFile || (file.name in retained)) return@forEach
            if (validName.matches(file.name)) {
                context.revokeUriPermission(FileProvider.getUriForFile(context, "${context.packageName}.clipboard", file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                thumbnails.remove(file.name)
                file.delete()
            } else if (file.name.startsWith(".import-")) file.delete()
        }
    }
}

object ClipboardImages {
    val store by lazy { ClipboardImageStore(appContext) }
    // Keep an image that is still on the system clipboard even if its history
    // row is removed. A later clipboard change releases this final reference.
    private val preferences get() = org.fcitx.fcitx5.android.FcitxApplication.getInstance().directBootAwareContext
        .getSharedPreferences("clipboard_image_state", Context.MODE_PRIVATE)
    var primaryFile: String?
        get() = preferences.getString("primary_file", null)
        set(value) { preferences.edit().putString("primary_file", value).apply() }
}

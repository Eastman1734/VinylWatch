package app.vinylwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Обложка для этикетки. Источники по порядку: встроенная в теги -> cover.jpg/folder.jpg/<трек>.jpg рядом -> thumbnail MediaStore.
 * Результат кешируется как квадрат 256x256 WebP; круглая маска накладывается в памяти (без clipPath при рисовании).
 * "Обложки нет" тоже кешируется (.none) — но только если ни один источник не упал с ошибкой.
 */
object Covers {
    private const val TAG = "Covers"
    private const val SIZE = 256
    private val sidecarNames = listOf("cover", "folder", "front", "album")
    private val sidecarExts = listOf("jpg", "jpeg", "png", "webp")

    /** Вызывать не на main. */
    fun load(ctx: Context, t: Track): ImageBitmap? {
        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        val key = sha1(t.uri.toString())
        val img = File(dir, "$key.webp")

        var square: Bitmap? = if (img.exists()) BitmapFactory.decodeFile(img.path) else null
        if (square == null) {
            var clean = true
            var raw: Bitmap? = null
            try { raw = embedded(ctx, t) } catch (e: Exception) { clean = false; Log.w(TAG, "embedded failed: $e") }
            Log.i(TAG, "embedded=${raw?.let { "${it.width}x${it.height}" }} title=${t.title} uri=${t.uri} path=${t.path}")
            if (raw == null) try { raw = sidecar(ctx, t) } catch (e: Exception) { clean = false; Log.w(TAG, "sidecar failed: $e") }
            if (raw == null) Log.i(TAG, "sidecar=none")
            if (raw == null) try { raw = thumb(ctx, t) } catch (e: Exception) { clean = false; Log.w(TAG, "thumb failed: $e") }
            if (raw == null) Log.i(TAG, "no cover found (clean=$clean)")
            if (raw == null) return null // «нет обложки» не кешируем: покладёшь jpg или скачаешь — подхватится сразу
            val sq = cropSquare(raw)
            img.outputStream().use { sq.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            square = sq
        }
        return circle(square).asImageBitmap()
    }

    /** Имя файла обложки = имя файла трека без расширения (для треков без пути — название, очищенное от недопустимых символов). */
    fun baseName(t: Track): String =
        (t.path?.let { File(it).nameWithoutExtension } ?: t.title).replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /** Папка своих обложек: /sdcard/Android/data/<pkg>/files/covers (туда же класть jpg вручную). */
    fun userCoverDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null)?.let { File(it, "covers") } ?: File(ctx.filesDir, "covers")).apply { mkdirs() }

    /** Сохранить найденную обложку (квадрат 256x256, jpg) — дальше её находит обычный поиск рядом/в covers. */
    fun saveSidecar(ctx: Context, t: Track, bmp: Bitmap) {
        val sq = cropSquare(bmp)
        File(userCoverDir(ctx), "${baseName(t)}.jpg").outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun embedded(ctx: Context, t: Track): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, t.uri)
            val b = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o) })
        } finally {
            r.release()
        }
    }

    /** Ищем <имя трека>.jpg в папках приложения (files/covers) и cover.jpg/folder.jpg/<имя>.jpg рядом с файлом. */
    private fun sidecar(ctx: Context, t: Track): Bitmap? {
        val f = t.path?.let { File(it) }
        val base = baseName(t)
        val own = listOfNotNull(ctx.getExternalFilesDir(null)?.let { File(it, "covers") }, File(ctx.filesDir, "covers"))
        val dirs = own.map { it to listOf(base) } + listOfNotNull(f?.parentFile?.let { it to (sidecarNames + base) })
        for ((dir, names) in dirs) for (n in names) for (e in sidecarExts) {
            val x = File(dir, "$n.$e")
            if (x.isFile) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(x.path, o)
                return BitmapFactory.decodeFile(x.path, BitmapFactory.Options().apply { inSampleSize = sample(o) })
            }
        }
        return null
    }

    private fun thumb(ctx: Context, t: Track): Bitmap? =
        if (t.uri.scheme == "content") ctx.contentResolver.loadThumbnail(t.uri, Size(SIZE, SIZE), null) else null

    private fun sample(o: BitmapFactory.Options): Int {
        var s = 1
        val m = min(o.outWidth, o.outHeight)
        while (m / (s * 2) >= SIZE) s *= 2
        return s
    }

    private fun cropSquare(b: Bitmap): Bitmap {
        val s = min(b.width, b.height)
        val c = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        return Bitmap.createScaledBitmap(c, SIZE, SIZE, true)
    }

    private fun circle(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p)
        return out
    }

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

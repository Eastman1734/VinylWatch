package app.vinylwatch

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** uri — content:// (MediaStore) или file:// (app-private папки). path нужен только для поиска cover.jpg рядом. */
data class Track(
    val uri: Uri,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val path: String?,
    val album: String? = null,
)

object Library {
    private val exts = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

    /** Папки приложения: сюда можно класть файлы без разрешений (adb push / run-as). */
    fun privateDirs(ctx: Context): List<File> = listOfNotNull(
        ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC),
        File(ctx.filesDir, "music").apply { mkdirs() },
    )

    fun scan(ctx: Context, mediaStoreAllowed: Boolean): List<Track> {
        val local = privateDirs(ctx)
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.extension.lowercase() in exts }
            .map { Track(Uri.fromFile(it), it.nameWithoutExtension, null, 0L, it.path) }
        val store = if (mediaStoreAllowed) mediaStore(ctx) else emptyList()
        return (store + local).sortedBy { it.title.lowercase() }
    }

    private fun mediaStore(ctx: Context): List<Track> {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM,
        )
        val sel = "${MediaStore.Audio.Media.DURATION} > 15000 AND " +
            "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0 AND " +
            "${MediaStore.Audio.Media.IS_NOTIFICATION}=0"
        val out = ArrayList<Track>()
        try {
            ctx.contentResolver.query(base, proj, sel, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                while (c.moveToNext()) {
                    val p = c.getString(iData)
                    val folder = p?.let { File(it).parentFile?.name } // без тега альбома MediaStore подставляет имя папки
                    out += Track(
                        uri = ContentUris.withAppendedId(base, c.getLong(iId)),
                        title = c.getString(iTitle) ?: "?",
                        artist = c.getString(iArtist)?.takeIf { it != MediaStore.UNKNOWN_STRING },
                        durationMs = c.getLong(iDur),
                        path = p,
                        album = c.getString(iAlbum)?.takeIf { it != MediaStore.UNKNOWN_STRING && it != folder },
                    )
                }
            }
        } catch (_: SecurityException) {
            // разрешение отозвали между проверкой и запросом
        }
        return out
    }

    /** Просит MediaStore проиндексировать файлы в /sdcard/Music (после adb push они сами могут не появиться). */
    suspend fun requestIndex(ctx: Context) = withContext(Dispatchers.IO) {
        val files = try {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                .walkTopDown().filter { it.isFile && it.extension.lowercase() in exts }.map { it.path }.toList()
        } catch (_: Exception) { emptyList() }
        if (files.isEmpty()) return@withContext
        // таймаут: без него «Обновить» навсегда зависнет, если сканер не вызовет callback
        withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val left = AtomicInteger(files.size)
                MediaScannerConnection.scanFile(ctx, files.toTypedArray(), null) { _, _ ->
                    if (left.decrementAndGet() == 0 && continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        Unit
    }
}

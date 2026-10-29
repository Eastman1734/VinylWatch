package app.vinylwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * Докачка обложек: MusicBrainz -> Cover Art Archive.
 *  - сначала ищем АЛЬБОМ (artist + album из тегов): обложка принадлежит альбому, один запрос на все треки альбома;
 *  - если альбома нет или не нашли — ищем ЗАПИСЬ (artist + название без «(Edited)», «(feat. …)», «[…]»).
 * Только по кнопке и по Wi-Fi. Сеть/лимит (503, таймаут, обрыв) — повтор с нарастающей паузой и уважением Retry-After;
 * если всё равно не вышло — трек НЕ запоминается как «не найден», следующее нажатие кнопки доделает остальное.
 * На время работы держим wake lock и Wi-Fi lock: иначе при погасшем экране часы режут сеть.
 * Правила MusicBrainz: осмысленный User-Agent с контактом и не чаще ~1 запроса в секунду.
 */
object CoverFetcher {
    private const val TAG = "CoverFetch"
    private const val USER_AGENT = "VinylWatch/1.0 ( https://github.com/Eastman1734/VinylWatch )"
    private const val RETRY_AFTER_MS = 14L * 24 * 3600 * 1000 // «не найдено» не переспрашиваем 14 дней
    private const val MB_GAP_MS = 1100L
    private var lastMbAt = 0L
    private val mbMutex = Mutex()

    class Result(val total: Int, val found: Int, val failed: Int)
    class HttpError(val code: Int, val retryAfterSec: Int?, msg: String) : IOException(msg)

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Вызывать на Dispatchers.IO: сканирование обложек и HTTP выполняются в фоне. */
    suspend fun fetchMissing(
        ctx: Context,
        tracks: List<Track>,
        onProgress: (done: Int, total: Int) -> Unit,
        onFound: () -> Unit,
    ): Result {
        val prefs = ctx.getSharedPreferences("coverfetch2", Context.MODE_PRIVATE) // v2: старые «не найдено» (до улучшенного поиска) не учитываем
        val now = System.currentTimeMillis()
        val todo = tracks.filter {
            currentCoroutineContext().ensureActive()
            it.artist != null && now - prefs.getLong(it.uri.toString(), 0L) > RETRY_AFTER_MS && Covers.load(ctx, it) == null
        }
        currentCoroutineContext().ensureActive()
        if (todo.isEmpty()) return Result(0, 0, 0)
        // треки одного альбома одного артиста делят одну обложку; трек без альбома — своя группа
        val groups = todo.groupBy { t ->
            if (t.album != null) norm(t.artist.orEmpty()) + "|" + norm(t.album) else t.uri.toString()
        }.values.toList()

        val wl = ctx.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vinylwatch:covers")
        @Suppress("DEPRECATION")
        val wfl = ctx.applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vinylwatch:covers")

        var done = 0
        var found = 0
        var failed = 0
        try {
            wl?.apply { setReferenceCounted(false); acquire(30 * 60_000L) }
            wfl?.apply { setReferenceCounted(false); acquire() }
            for (g in groups) {
                currentCoroutineContext().ensureActive()
                if (!onWifi(ctx)) throw IOException("Wi-Fi отключён")
                onProgress(done, todo.size)
                val lead = g.first()
                try {
                    val f = findCover(lead)
                    currentCoroutineContext().ensureActive()
                    if (f != null) {
                        try {
                            g.forEach {
                                currentCoroutineContext().ensureActive()
                                Covers.saveSidecar(ctx, it, f.bmp)
                            }
                        } finally { f.bmp.recycle() }
                        Log.i(TAG, "saved: ${lead.artist} - ${lead.album ?: lead.title} ← ${f.info} (${g.size} tr.)")
                        found += g.size
                        onFound()
                    } else {
                        val t = System.currentTimeMillis()
                        prefs.edit().apply { g.forEach { putLong(it.uri.toString(), t) } }.apply()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed += g.size
                    Log.w(TAG, "gave up on ${lead.artist} - ${lead.title}: $e")
                }
                done += g.size
            }
        } finally {
            try { if (wfl?.isHeld == true) wfl.release() } catch (_: Exception) {}
            try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
        }
        return Result(todo.size, found, failed)
    }

    private class Found(val bmp: Bitmap, val info: String)
    private class AlbumCand(val id: String, val title: String, val year: Int, val rank: Int, val bad: Boolean)
    private class Cand(val release: String, val group: String?, val title: String, val year: Int, val rank: Int, val bad: Boolean)

    /**
     * Порядок: 1) альбом из тегов, если он не сборник/лайв/ремикс; 2) запись -> самый РАННИЙ официальный альбом/сингл
     * (сборники и Various Artists уходят в конец); 3) альбом из тегов, даже если сборник (последний шанс).
     */
    private suspend fun findCover(t: Track): Found? {
        val artist = t.artist ?: return null

        val albums = t.album?.let { album -> retry("album $album") { searchReleaseGroups(artist, album) } } ?: emptyList()
        if (t.album != null) Log.i(TAG, "$artist - ${t.album}: ${albums.size} album candidates, из них сборник/лайв/ремикс: ${albums.count { it.bad }}")

        for (a in albums.filter { !it.bad }.take(3)) {
            decode(retry("caa group") { caa("release-group/${a.id}") })
                ?.let { return Found(it, "альбом из тегов «${a.title}» ${a.year}") }
        }

        val cands = retry("recording ${t.title}") { searchRecordings(artist, t.title) }
        Log.i(TAG, "$artist - ${t.title}: ${cands.size} recording candidates")
        for (c in cands.take(4)) {
            var img = retry("caa release") { caa("release/${c.release}") }
            if (img == null && c.group != null) img = retry("caa group") { caa("release-group/${c.group}") }
            decode(img)?.let {
                return Found(it, "запись → «${c.title}» ${c.year}" + if (c.bad) " [сборник/лайв/ремикс]" else "")
            }
        }

        for (a in albums.filter { it.bad }.take(2)) {
            decode(retry("caa group") { caa("release-group/${a.id}") })
                ?.let { return Found(it, "альбом из тегов (сборник) «${a.title}» ${a.year}") }
        }
        return null
    }

    // ---------- поиск ----------

    private val badTypes = setOf("Compilation", "Live", "Remix", "DJ-mix", "Mixtape/Street", "Demo", "Soundtrack")

    private fun yearOf(date: String): Int = date.take(4).toIntOrNull() ?: 9999

    private suspend fun searchReleaseGroups(artist: String, album: String): List<AlbumCand> {
        val q = "releasegroup:\"${esc(clean(album))}\" AND artist:\"${esc(artist)}\""
        val body = mbGet("https://musicbrainz.org/ws/2/release-group/?query=${enc(q)}&fmt=json&limit=6") ?: return emptyList()
        val arr = JSONObject(String(body)).optJSONArray("release-groups") ?: return emptyList()
        val out = ArrayList<AlbumCand>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optInt("score", 0) < 90 || norm(clean(r.optString("title"))) != norm(clean(album))) continue
            val sec = r.optJSONArray("secondary-types")
            val bad = sec != null && (0 until sec.length()).any { sec.optString(it) in badTypes }
            var rank = when (r.optString("primary-type")) { "Album" -> 3; "EP" -> 2; "Single" -> 1; else -> 0 }
            if (bad) rank -= 6
            out += AlbumCand(r.getString("id"), r.optString("title"), yearOf(r.optString("first-release-date")), rank, bad)
        }
        return out.sortedWith(compareByDescending<AlbumCand> { it.rank }.thenBy { it.year })
    }

    private suspend fun searchRecordings(artist: String, title: String): List<Cand> {
        val ct = clean(title)
        val q = "recording:\"${esc(ct)}\" AND artist:\"${esc(artist)}\""
        val body = mbGet("https://musicbrainz.org/ws/2/recording/?query=${enc(q)}&fmt=json&limit=6") ?: return emptyList()
        val recs = JSONObject(String(body)).optJSONArray("recordings") ?: return emptyList()
        val out = ArrayList<Cand>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            val score = r.optInt("score", 0)
            if (score < 90 || !(norm(clean(r.optString("title"))) == norm(ct) || score == 100)) continue
            val rels = r.optJSONArray("releases") ?: continue
            for (j in 0 until rels.length()) {
                val x = rels.getJSONObject(j)
                val rg = x.optJSONObject("release-group")
                val sec = rg?.optJSONArray("secondary-types")
                val ac = x.optJSONArray("artist-credit")
                val various = ac != null && (0 until ac.length()).any {
                    ac.optJSONObject(it)?.optString("name").orEmpty().contains("Various Artists", ignoreCase = true)
                }
                val bad = various || (sec != null && (0 until sec.length()).any { sec.optString(it) in badTypes })
                var rank = 0
                if (x.optString("status") == "Official") rank += 4
                rank += when (rg?.optString("primary-type").orEmpty()) { "Album" -> 3; "EP" -> 2; "Single" -> 1; else -> 0 }
                if (bad) rank -= 6
                out += Cand(
                    x.getString("id"), rg?.optString("id")?.takeIf { it.isNotEmpty() },
                    x.optString("title"), yearOf(x.optString("date")), rank, bad,
                )
            }
        }
        // лучший ранг, а среди равных — самый ранний выпуск (оригинал, а не переиздание)
        return out.sortedWith(compareByDescending<Cand> { it.rank }.thenBy { it.year })
    }

    // ---------- сеть ----------

    /** Повтор при сетевых ошибках/лимитах: 2,5 → 5 → 10 → 20 с; Retry-After сервера учитывается. */
    private suspend fun <T> retry(what: String, block: suspend () -> T): T {
        var wait = 2500L
        var attempt = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= 4) throw e
                val ra = (e as? HttpError)?.retryAfterSec?.let { it * 1000L } ?: 0L
                val ms = min(max(wait, ra), 30_000L)
                Log.w(TAG, "$what: ${e.message} — повтор $attempt через ${ms} мс")
                delay(ms)
                wait *= 2
                attempt++
            }
        }
    }

    /** Mutex защищает лимит MusicBrainz даже при отмене и повторном запуске. */
    private suspend fun mbGet(url: String): ByteArray? = mbMutex.withLock {
        val wait = MB_GAP_MS - (SystemClock.elapsedRealtime() - lastMbAt)
        if (wait > 0) delay(wait)
        currentCoroutineContext().ensureActive()
        try {
            get(url, "application/json")
        } finally {
            lastMbAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun caa(path: String): ByteArray? = get("https://coverartarchive.org/$path/front-500", "image/*")

    /** null = 404 (такого нет), HttpError/IOException = временная ошибка. */
    private suspend fun get(url: String, accept: String): ByteArray? = withContext(Dispatchers.IO) {
        coroutineScope {
            ensureActive()
            val c = URL(url).openConnection() as HttpURLConnection
            // Отмена закрывает сокет, включая ожидание HTTP-заголовков/тела.
            val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { c.disconnect() }
            }
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 25_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", accept)
                val code = c.responseCode
                ensureActive()
                when {
                    code == 404 -> null
                    code !in 200..299 -> throw HttpError(
                        code, c.getHeaderField("Retry-After")?.trim()?.toIntOrNull(), "HTTP $code for $url",
                    )
                    else -> c.inputStream.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(bytes.size() + n <= 12 * 1024 * 1024) { "Слишком большая обложка" }
                            bytes.write(buffer, 0, n)
                        }
                        bytes.toByteArray()
                    }
                }
            } finally {
                disconnect.cancel()
                c.disconnect()
            }
        }
    }

    // ---------- утилиты ----------

    private fun decode(img: ByteArray?): Bitmap? = img?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Убираем хвосты в скобках: «(Edited)», «(feat. …)», «[Remastered]», «(The Guvnor Mix)». */
    private fun clean(s: String): String {
        val c = s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim()
        return if (c.isEmpty()) s else c
    }
}

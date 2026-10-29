package app.vinylwatch

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vinylwatch.audio.ScratchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/** Снимок для сервиса/MediaSession. Позицию сюда НЕ кладём — иначе поток эмитил бы постоянно. */
data class Snap(val track: Track? = null, val playing: Boolean = false, val loading: Boolean = false, val revision: Long = 0)

class Deck(private val ctx: Context, private val scope: CoroutineScope) {
    var tracks by mutableStateOf(emptyList<Track>())
    var current by mutableStateOf<Track?>(null); private set
    var engine by mutableStateOf<ScratchEngine?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    val snap = MutableStateFlow(Snap())
    private var job: Job? = null
    private var engineJob: Job? = null

    // --- докачка обложек ---
    var coverStatus by mutableStateOf<String?>(null); private set
    var coverRunning by mutableStateOf(false); private set
    var coverVersion by mutableIntStateOf(0); private set // растёт при каждой найденной обложке — UI перечитывает
    private var coverJob: Job? = null
    private var coverGeneration = 0
    private val main = Handler(Looper.getMainLooper())

    /** Нажатие: запуск; повторное нажатие во время работы — остановка. */
    fun fetchCovers() {
        if (coverRunning) {
            coverGeneration++
            coverJob?.cancel(); coverRunning = false; coverStatus = "Остановлено"; return
        }
        if (!CoverFetcher.onWifi(ctx)) { coverStatus = "Нужен Wi-Fi"; return }
        val list = tracks
        coverRunning = true
        coverStatus = "Ищу…"
        val generation = ++coverGeneration
        val previous = coverJob
        coverJob = scope.launch(Dispatchers.IO) {
            try {
                previous?.cancelAndJoin()
                val r = CoverFetcher.fetchMissing(
                    ctx, list,
                    onProgress = { d, t -> main.post {
                        if (coverGeneration == generation) coverStatus = "Обложки $d/$t"
                    } },
                    onFound = { main.post { if (coverGeneration == generation) coverVersion++ } },
                )
                if (isActive) main.post {
                    if (coverGeneration == generation) {
                        coverStatus = if (r.total == 0) "Все на месте"
                        else "Найдено ${r.found} из ${r.total}" + if (r.failed > 0) ", не дошло ${r.failed} — нажми снова" else ""
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                main.post { if (coverGeneration == generation) coverStatus = "Ошибка: ${e.message}" }
            } finally {
                main.post { if (coverGeneration == generation) coverRunning = false }
            }
        }
    }

    fun open(t: Track) {
        if (t.uri == current?.uri && engine != null && error == null) return
        job?.cancel()
        engineJob?.cancel()
        engine?.release()
        val e = ScratchEngine()
        engine = e; current = t; loading = true; error = null
        e.onEnded = { if (engine === e) next() }
        e.onFailure = { x ->
            if (engine === e) {
                error = x.message ?: x.javaClass.simpleName
                e.release()
                loading = false
                publish()
            }
        }
        engineJob = scope.launch { e.changes.collect { if (engine === e) publish() } }
        publish()
        job = scope.launch {
            try {
                e.load(ctx, t.uri)
                if (engine === e) { loading = false; play() }
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                if (engine === e) {
                    error = x.message ?: x.javaClass.simpleName
                    e.release()
                    loading = false
                    publish()
                }
            }
        }
    }

    fun play() {
        val e = engine ?: return
        e.play()
        if (e.playing) ensureService()
        publish()
    }
    fun pause() { engine?.pause(); publish() }
    fun togglePlay() { if (engine?.playing == true) pause() else play() }

    fun next() {
        val list = tracks
        val cur = current ?: return
        if (list.size <= 1) {
            engine?.seekToFraction(0f); play()
        } else {
            open(list[(list.indexOf(cur) + 1).mod(list.size)])
        }
    }

    fun seekMs(ms: Long) {
        val e = engine ?: return
        val d = e.durationSeconds
        if (d > 0) e.seekToFraction((ms / 1000.0 / d).toFloat())
    }

    /** Полная остановка: освобождаем PCM (десятки МБ) и аудиопоток. */
    fun stop() {
        job?.cancel()
        engineJob?.cancel(); engineJob = null
        engine?.release()
        engine = null; current = null; loading = false; error = null
        publish()
    }

    private fun publish() { snap.value = Snap(current, engine?.playing == true, loading, engine?.changes?.value ?: 0) }

    private fun ensureService() {
        try {
            ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
        } catch (_: Exception) {
            // запуск FGS из фона может быть запрещён; музыка при этом продолжит играть, пока жив процесс
        }
    }
}

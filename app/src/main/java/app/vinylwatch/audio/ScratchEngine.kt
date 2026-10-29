package app.vinylwatch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * "Вертушка": трек целиком в памяти, указатель чтения (дробный, в сэмплах) двигается со скоростью `vel`
 * на каждый выходной сэмпл. vel = 1 — норма, 0 — стоп, отрицательная — реверс.
 *
 * Два режима вывода (энергопотребление):
 *  - DEEP: обычное воспроизведение. AudioTrack POWER_SAVING, блоки по 2048 кадров (~46 мс) -> ~21 пробуждение/с.
 *          При vel == 1 блок просто копируется из памяти (arraycopy), без пересчёта по сэмплам.
 *  - FAST: пока палец на пластинке (+4 с после). AudioTrack LOW_LATENCY, блоки по 256 кадров (~6 мс).
 * Переключение: недоигранный буфер сбрасывается, указатель возвращается в реально слышимую позицию.
 */
class ScratchEngine {
    @Volatile var playing = false; private set
    @Volatile var muted = false
    @Volatile var ready = false; private set
    @Volatile var positionFrames = 0.0; private set   // СЛЫШИМАЯ позиция (с учётом буфера вывода)
    @Volatile var velocityView = 0f; private set      // для UI: крутится ли пластинка
    var onEnded: (() -> Unit)? = null
    var onFailure: ((Exception) -> Unit)? = null
    private val revision = MutableStateFlow(0L)
    /** События управления, без периодических обновлений позиции. */
    val changes = revision.asStateFlow()
    private fun changed() { revision.update { it + 1 } }

    @Volatile private var scratching = false
    @Volatile private var scratchVel = 0f
    @Volatile private var running = false
    private val seekReq = AtomicReference<Double?>(null)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    private val lifecycleLock = Any()
    @Volatile private var released = false

    // PCM заполняется декодером в фоне; плеер читает только уже декодированную часть (decodedFrames)
    @Volatile private var pcm = ShortArray(0)
    @Volatile private var totalFrames = 0     // сначала оценка по длительности, после декодирования — точное значение
    @Volatile private var decodedFrames = 0
    @Volatile private var decodeDone = false
    @Volatile var sampleRate = 44100; private set
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- состояние только аудиопотока ---
    private val renderer = PcmRenderer()
    private var pos: Double
        get() = renderer.position
        set(value) { renderer.position = value }
    private var vel: Float
        get() = renderer.velocity
        set(value) { renderer.velocity = value }
    private var active: AudioTrack? = null
    private var fastMode = false
    private var headBase = 0L
    private var cum = 0L                   // кадров записано в active с последнего begin()
    private val ringS = DoubleArray(RING)  // кольцо: позиция в начале/конце каждого записанного блока
    private val ringE = DoubleArray(RING)
    private val ringC = LongArray(RING)
    private val ringL = IntArray(RING)
    private var ringN = 0
    private var ringHead = 0

    val isScratching: Boolean get() = scratching
    val positionSeconds: Double get() = positionFrames / sampleRate
    val durationSeconds: Double get() = if (ready) totalFrames.toDouble() / sampleRate else 0.0
    val progress: Float
        get() = if (totalFrames > 1) (positionFrames / (totalFrames - 1)).toFloat().coerceIn(0f, 1f) else 0f

    /** Возвращается, как только декодирована первая секунда — остальное декодируется в фоне во время игры. */
    suspend fun load(ctx: Context, uri: Uri) {
        if (released) throw CancellationException()
        val first = CompletableDeferred<Unit>()
        scope.launch {
            try {
                val n = PcmDecoder.decode(
                    ctx, uri, { isActive },
                    onFormat = { sr, durUs -> allocate(sr, durUs) },
                    onProgress = { frames ->
                        decodedFrames = frames
                        if (frames >= sampleRate) { startAudio(); first.complete(Unit) }
                    },
                )
                decodedFrames = n; totalFrames = n; decodeDone = true
                changed()
                if (n > 1) { startAudio(); first.complete(Unit) }
                else first.completeExceptionally(IllegalStateException("пустой трек"))
            } catch (e: CancellationException) {
                first.cancel()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "decode failed", e)
                if (decodedFrames > 1) { // частично декодированное играем как есть
                    totalFrames = decodedFrames; decodeDone = true
                    changed()
                    startAudio(); first.complete(Unit)
                } else {
                    first.completeExceptionally(e)
                }
            }
        }
        first.await()
    }

    private fun allocate(sr: Int, durUs: Long): ShortArray = synchronized(lifecycleLock) {
        if (released) throw CancellationException()
        require(sr > 0) { "Некорректная частота: $sr" }
        sampleRate = sr
        val maxFrames = min(sr.toLong() * 60 * 10, Int.MAX_VALUE / 2L - sr).toInt()
        // потолок 10 минут (~100 МБ при 44,1 кГц): длиннее — обрезаем
        val exact = if (durUs > 0) (durUs / 1_000_000.0 * sr).coerceAtMost(maxFrames.toDouble()).toInt() else 0
        val cap = if (exact > 0) min(maxFrames, (exact * 1.03).toInt() + sr) else sr * 60 * 6
        totalFrames = if (exact > 0) min(exact, cap) else cap
        val a = ShortArray(cap * 2)
        pcm = a
        a
    }

    private fun startAudio() = synchronized(lifecycleLock) {
        if (released) throw CancellationException()
        if (!started.compareAndSet(false, true)) return
        positionFrames = 0.0
        ready = true
        running = true
        thread = Thread({ runLoop() }, "scratch-audio").also { it.start() }
        changed()
    }

    // ---- управление из UI ----
    fun play() { if (!ready || released) return; playing = true; changed(); wake() }
    fun pause() { playing = false; changed(); wake() }
    fun togglePlay() { if (playing) pause() else play() }
    fun beginScratch() { if (!ready || released) return; scratchVel = 0f; scratching = true; changed(); wake() }
    fun setScratchVelocity(v: Float) { scratchVel = v }
    fun endScratch() { scratching = false; changed(); wake() }
    fun seekToFraction(f: Float) {
        if (!ready) return
        val p = f.coerceIn(0f, 1f).toDouble() * (totalFrames - 1)
        positionFrames = p
        seekReq.set(p)
        changed()
        wake()
    }
    fun release() {
        synchronized(lifecycleLock) {
            released = true
            running = false; playing = false; ready = false
            onEnded = null; onFailure = null
            pcm = ShortArray(0)
        }
        scope.cancel()
        wake()
        thread?.join(500)
        thread = null
        changed()
    }

    private fun wake() = lock.withLock { cond.signalAll() }
    private fun alpha(tauSec: Double, sr: Int) = (1.0 - exp(-1.0 / (tauSec * sr))).toFloat()

    private fun buildTrack(sr: Int, fast: Boolean): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = if (fast) FAST_CHUNK else DEEP_CHUNK
        val bytes = max(minBytes, chunk * 4 * (if (fast) 2 else 3))
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(if (fast) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .setBufferSizeInBytes(bytes)
            .build()
    }

    private fun rawHead(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    private fun playedFrames(t: AudioTrack): Long =
        ((rawHead(t) - headBase) and 0xFFFFFFFFL).coerceAtMost(cum)

    /** Сбросить вывод `to` и начать писать с позиции p (старый буфер выбрасывается — реакция на действие мгновенная). */
    private fun begin(to: AudioTrack, fast: Boolean, p: Double) {
        try { active?.pause(); active?.flush() } catch (_: Exception) {}
        active = to; fastMode = fast; pos = p
        ringN = 0; ringHead = 0; cum = 0L
        try { to.pause(); to.flush() } catch (_: Exception) {}
        headBase = rawHead(to)
        to.play()
    }

    private fun pushRing(p0: Double, n: Int) {
        ringS[ringHead] = p0; ringE[ringHead] = pos; ringC[ringHead] = cum; ringL[ringHead] = n
        ringHead = (ringHead + 1) % RING
        if (ringN < RING) ringN++
    }

    /** Что слышно прямо сейчас: по числу реально проигранных кадров находим блок в кольце и интерполируем внутри него. */
    private fun audibleNow(): Double {
        val a = active ?: return pos
        if (ringN == 0) return pos
        val played = playedFrames(a)
        for (k in 1..ringN) {
            val i = (ringHead - k + RING) % RING
            val endC = ringC[i]
            val len = ringL[i]
            val startC = endC - len
            if (played >= startC) {
                return if (played >= endC) ringE[i]
                else ringS[i] + (ringE[i] - ringS[i]) * ((played - startC).toDouble() / len)
            }
        }
        return ringS[(ringHead - ringN + RING) % RING]
    }

    /** Один блок из n кадров -> out. Возвращает true, если упёрлись в конец трека. */
    private fun renderChunk(
        n: Int, out: ShortArray, sc: Boolean, target: Float, muted: Boolean, limit: Int, done: Boolean,
        aS: Float, aU: Float, aD: Float,
    ): Boolean {
        return renderer.render(pcm, n, out, sc, target, muted, limit + 1, done, aS, aU, aD)
    }

    // ---- аудиопоток ----
    private fun runLoop() {
        if (!running) return
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = sampleRate
        val aScratch = alpha(0.003, sr)
        val aUp = alpha(0.09, sr)
        val aDown = alpha(0.22, sr)
        val out = ShortArray(DEEP_CHUNK * 2)
        var deep: AudioTrack? = null
        var fast: AudioTrack? = null
        var lastScratchAt = 0L
        var lastPlaying = false
        var lastMuted = false
        var idleSince = 0L
        pos = 0.0; vel = 0f

        try {
            val deepT = buildTrack(sr, false)
            deep = deepT
            begin(deepT, false, 0.0)

            while (running) {
                val now = SystemClock.elapsedRealtime()
                val sc = scratching
                if (sc) lastScratchAt = now

                // 1) режим вывода
                if (sc && !fastMode) {
                    val f = fast ?: buildTrack(sr, true).also { fast = it }
                    begin(f, true, audibleNow())
                } else if (fastMode && !sc && now - lastScratchAt > FAST_GRACE_MS) {
                    begin(deepT, false, audibleNow())
                    fast?.release()
                    fast = null // не держим low-latency вывод на протяжении всего трека
                }
                // 2) перемотка тонармом
                seekReq.getAndSet(null)?.let {
                    begin(active!!, fastMode, it.coerceIn(0.0, (totalFrames - 1).toDouble()))
                    positionFrames = pos
                    changed()
                }
                // 3) play/pause/mute: в DEEP-буфере ещё ~140 мс старого звука — сбрасываем, чтобы реакция была сразу
                val pl = playing
                val mu = muted
                if (!fastMode && (pl != lastPlaying || mu != lastMuted)) begin(active!!, false, audibleNow())
                lastPlaying = pl; lastMuted = mu

                // 4) рендер и запись (блокирующая запись задаёт темп)
                val n = if (fastMode) FAST_CHUNK else DEEP_CHUNK
                val target = if (sc) scratchVel else if (pl) 1f else 0f
                val p0 = pos
                val done = decodeDone              // сначала done, потом decodedFrames: если done — число кадров уже финальное
                val limit = decodedFrames - 1
                val atEnd = renderChunk(n, out, sc, target, mu, limit, done, aScratch, aUp, aDown)
                val output = active!!
                if (!PcmOutput.writeFully(out, n * 2, { running }) { offset, count ->
                        output.write(out, offset, count)
                    }) break
                cum += n
                pushRing(p0, n)
                positionFrames = audibleNow()
                velocityView = vel

                if (atEnd && pl && !sc) {
                    // write() лишь ставит данные в очередь: сначала даём доиграть хвост.
                    val end = cum
                    output.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(track: AudioTrack) { wake() }
                        override fun onPeriodicNotification(track: AudioTrack) = Unit
                    }, main)
                    val marker = ((headBase + end) and 0xFFFFFFFFL).toInt()
                    output.setNotificationMarkerPosition(if (marker == 0) 1 else marker)
                    // общий дедлайн: если вывод встал и позиция не растёт, не ждём вечно (иначе «играет» зависнет)
                    val deadline = SystemClock.elapsedRealtime() + (end - playedFrames(output)) * 1000L / sr + TAIL_GRACE_MS
                    lock.withLock {
                        while (running && playing && !scratching && seekReq.get() == null && playedFrames(output) < end) {
                            val left = deadline - SystemClock.elapsedRealtime()
                            if (left <= 0L) { Log.w(TAG, "tail wait timed out"); break }
                            // Одно ожидание до конца очереди; timeout — запасной путь для устройств без marker callback.
                            val remainingMs = (end - playedFrames(output)) * 1000L / sr + 100L
                            cond.await(min(left, remainingMs), TimeUnit.MILLISECONDS)
                        }
                    }
                    output.setNotificationMarkerPosition(0)
                    if (!running) break
                    if (playing && !scratching && seekReq.get() == null) {
                        playing = false; vel = 0f; velocityView = 0f
                        positionFrames = pos
                        changed()
                        main.post { if (!released) onEnded?.invoke() }
                    }
                }

                // 5) тишина -> усыпляем поток и вывод (0 пробуждений на паузе)
                val idle = !sc && !playing && abs(vel) < 0.002f
                if (idle) {
                    if (idleSince == 0L) {
                        idleSince = now
                    } else if (now - idleSince > IDLE_MS) {
                        positionFrames = pos; velocityView = 0f
                        active!!.pause(); active!!.flush()
                        if (fastMode) {
                            fast?.release(); fast = null
                            active = deepT; fastMode = false
                        }
                        changed()
                        lock.withLock { while (running && !playing && !scratching && seekReq.get() == null) cond.await() }
                        if (!running) break
                        idleSince = 0L
                        begin(active!!, fastMode, pos)
                    }
                } else {
                    idleSince = 0L
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop died", e)
            main.post { if (!released) onFailure?.invoke(e) }
        } finally {
            for (t in listOf(deep, fast)) {
                if (t != null) {
                    try { t.stop() } catch (_: Exception) {}
                    t.release()
                }
            }
            active = null
            running = false; playing = false; ready = false; velocityView = 0f
            changed()
            if (released) pcm = ShortArray(0)
        }
    }

    private companion object {
        const val TAG = "ScratchEngine"
        const val DEEP_CHUNK = 2048
        const val FAST_CHUNK = 256
        const val RING = 16
        const val FAST_GRACE_MS = 4000L
        const val IDLE_MS = 500L
        const val TAIL_GRACE_MS = 2000L
    }
}

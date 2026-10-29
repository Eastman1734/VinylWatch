package app.vinylwatch.audio

import android.content.Context
import android.media.MediaCodec
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Потоковый декодер: файл -> стерео PCM16 (L,R,L,R...) в массив, который выдаёт onFormat.
 * onProgress вызывается по мере декодирования — плеер стартует, не дожидаясь конца файла.
 * Моно дублируется, лишние каналы отбрасываются. Если массив кончился — декодирование обрезается.
 * Возвращает число декодированных кадров.
 */
object PcmDecoder {
    fun decode(
        ctx: Context,
        uri: Uri,
        alive: () -> Boolean,
        onFormat: (sampleRate: Int, durationUs: Long) -> ShortArray,
        onProgress: (frames: Int) -> Unit,
    ): Int {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            require(idx >= 0 && fmt != null) { "нет аудиодорожки" }
            ex.selectTrack(idx)

            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            c.configure(fmt, null, null, 0)
            c.start()

            var out: ShortArray? = null
            var n = 0 // записано сэмплов (interleaved stereo)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!alive()) throw CancellationException()
                var progressed = false

                // 1) кормим кодек, пока есть свободные входные буферы (без ожидания)
                while (!inDone) {
                    if (!alive()) throw CancellationException()
                    val i = c.dequeueInputBuffer(0)
                    if (i < 0) break
                    val size = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inDone = true
                    } else {
                        c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                    progressed = true
                }

                // 2) забираем всё готовое; ждём (5 мс) только если в этом круге ничего не сдвинулось
                while (true) {
                    if (!alive()) throw CancellationException()
                    val o = c.dequeueOutputBuffer(info, if (progressed) 0L else 5_000L)
                    if (o >= 0) {
                        progressed = true
                        if (info.size == 0) {
                            c.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outDone = true; break }
                            continue
                        }
                        val buf = requireNotNull(c.getOutputBuffer(o)) { "Нет PCM-буфера" }
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        require(ch > 0) { "Нет аудиоканалов" }
                        buf.order(ByteOrder.nativeOrder())
                        val floats = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buf.asFloatBuffer() else null
                        val sb = if (floats == null) buf.asShortBuffer() else null
                        val frames = (floats?.remaining() ?: sb!!.remaining()) / ch
                        var full = false
                        if (frames > 0) {
                            val dst = out ?: onFormat(sr, durUs).also { out = it }
                            val take = minOf(frames, (dst.size - n) / 2)
                            if (floats != null) {
                                repeat(take) {
                                    fun sample(): Short {
                                        val f = floats.get()
                                        return if (f.isNaN()) 0 else (f.coerceIn(-1f, 1f) * 32768f)
                                            .toInt().coerceIn(-32768, 32767).toShort()
                                    }
                                    val l = sample()
                                    val r = if (ch > 1) sample() else l
                                    repeat(maxOf(0, ch - 2)) { floats.get() }
                                    dst[n + it * 2] = l; dst[n + it * 2 + 1] = r
                                }
                            } else if (ch == 2) {
                                sb!!.get(dst, n, take * 2) // уже interleaved stereo — копируем пачкой
                            } else if (ch == 1) {
                                repeat(take) { val s = sb!!.get(); dst[n + it * 2] = s; dst[n + it * 2 + 1] = s }
                            } else {
                                repeat(take) {
                                    val l = sb!!.get(); val r = sb.get()
                                    repeat(ch - 2) { sb.get() }
                                    dst[n + it * 2] = l; dst[n + it * 2 + 1] = r
                                }
                            }
                            n += take * 2
                            if (take < frames) full = true
                            onProgress(n / 2)
                        }
                        c.releaseOutputBuffer(o, false)
                        if (full || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outDone = true; break }
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val nf = c.outputFormat
                        val newSr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        require(out == null || newSr == sr) { "Частота PCM изменилась во время декодирования" }
                        sr = newSr
                        ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (nf.containsKey(MediaFormat.KEY_PCM_ENCODING)) nf.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            "Неподдерживаемый формат PCM: $encoding"
                        }
                        progressed = true
                    } else {
                        break // TRY_AGAIN_LATER
                    }
                }
            }
            return n / 2
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } finally { ex.release() }
        }
    }
}

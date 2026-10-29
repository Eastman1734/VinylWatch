package app.vinylwatch.audio

import kotlin.math.abs
import kotlin.math.max

/** Чистая PCM-математика: без Android, синхронизации и выделений памяти в цикле. */
internal class PcmRenderer {
    var position = 0.0
    var velocity = 0f

    fun render(
        pcm: ShortArray, n: Int, out: ShortArray, scratching: Boolean, target: Float,
        muted: Boolean, frames: Int, done: Boolean, aScratch: Float, aUp: Float, aDown: Float,
    ): Boolean {
        val count = frames.coerceIn(0, pcm.size / 2)
        var p = position
        var v = velocity
        if (count == 0) {
            java.util.Arrays.fill(out, 0, n * 2, 0.toShort())
            return done
        }
        if (!scratching && target == 1f && v == 1f && p == Math.rint(p)) {
            val ip = p.toInt()
            if (ip >= 0 && ip.toLong() + n <= count) {
                if (muted) java.util.Arrays.fill(out, 0, n * 2, 0.toShort())
                else System.arraycopy(pcm, ip * 2, out, 0, n * 2)
                position = p + n
                return done && position >= count
            }
        }
        var atEnd = false
        for (i in 0 until n) {
            val alpha = if (scratching) aScratch else if (target > v) aUp else aDown
            v += (target - v) * alpha
            if (!scratching && abs(target - v) < 1e-4f) {
                v = target
                if (target == 1f) p = Math.rint(p)
            }
            if (p >= count) {
                // После перемотки вперёд декодер может ещё не догнать позицию.
                // Реверс должен позволять вернуться в готовую часть трека.
                if (v < 0f) p = max(0.0, p + v)
                if (p >= count) {
                    out[i * 2] = 0; out[i * 2 + 1] = 0
                    if (done) atEnd = true
                    continue
                }
            }
            p = max(0.0, p)
            if (muted || (!scratching && target == 0f && abs(v) < 0.002f)) {
                out[i * 2] = 0; out[i * 2 + 1] = 0
                if (!scratching && target == 0f && abs(v) < 0.002f) v = 0f
            } else {
                val ip = p.toInt()
                val fr = (p - ip).toFloat()
                val i0 = ip * 2
                val i1 = minOf(ip + 1, count - 1) * 2
                out[i * 2] = (pcm[i0] + (pcm[i1] - pcm[i0]) * fr).toInt().toShort()
                out[i * 2 + 1] = (pcm[i0 + 1] + (pcm[i1 + 1] - pcm[i0 + 1]) * fr).toInt().toShort()
            }
            p = max(0.0, p + v)
            if (done && p >= count) atEnd = true
        }
        position = p; velocity = v
        return atEnd
    }
}

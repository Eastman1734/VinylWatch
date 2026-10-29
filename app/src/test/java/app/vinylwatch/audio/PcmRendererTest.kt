package app.vinylwatch.audio

import org.junit.Assert.*
import org.junit.Test

class PcmRendererTest {
    private fun stereo(vararg values: Int) = values.flatMap { listOf(it.toShort(), (-it).toShort()) }.toShortArray()
    private fun PcmRenderer.block(
        source: ShortArray, frames: Int = source.size / 2, n: Int = frames,
        target: Float = 1f, scratch: Boolean = false, muted: Boolean = false, done: Boolean = true,
    ): Pair<ShortArray, Boolean> {
        val output = ShortArray(n * 2) { 12345 }
        val end = render(source, n, output, scratch, target, muted, frames, done, 1f, 1f, 1f)
        return output to end
    }

    @Test fun normalSpeedPreservesEveryStereoSampleAcrossBlocks() {
        val source = stereo(100, 200, 300, 400, 500, 600)
        val r = PcmRenderer().apply { velocity = 1f }
        val a = r.block(source, n = 3)
        val b = r.block(source, n = 3)
        assertArrayEquals(source, a.first + b.first)
        assertFalse(a.second)
        assertTrue(b.second)
    }

    @Test fun fractionalPositionInterpolatesBothChannels() {
        val r = PcmRenderer().apply { position = 0.5; velocity = 0.5f }
        val (out, _) = r.block(stereo(0, 1000, 2000), n = 2, target = 0.5f, scratch = true)
        assertArrayEquals(stereo(500, 1000), out)
    }

    @Test fun endOfTrackIsPaddedWithSilenceInsteadOfRepeatingLastSample() {
        val r = PcmRenderer().apply { velocity = 1f }
        val (out, end) = r.block(stereo(100, 200, 300), n = 5)
        assertArrayEquals(stereo(100, 200, 300, 0, 0), out)
        assertTrue(end)
    }

    @Test fun singleFrameClipDoesNotReadPastItsBuffer() {
        val (out, end) = PcmRenderer().block(stereo(32000), n = 3)
        assertArrayEquals(stereo(32000, 0, 0), out)
        assertTrue(end)
    }

    @Test fun reverseStopsAtBeginningWithoutNegativeIndexes() {
        val r = PcmRenderer().apply { position = 2.0; velocity = -1f }
        val (out, end) = r.block(stereo(100, 200, 300), n = 4, target = -1f, scratch = true)
        assertArrayEquals(stereo(300, 200, 100, 100), out)
        assertFalse(end)
        assertEquals(0.0, r.position, 0.0)
    }

    @Test fun decoderUnderrunWaitsWithoutEndingTrackOrReadingUndecodedPcm() {
        val r = PcmRenderer().apply { position = 2.0; velocity = 1f }
        val (out, end) = r.block(stereo(100, 200, 32767, 32767), frames = 2, n = 4, done = false)
        assertArrayEquals(ShortArray(8), out)
        assertFalse(end)
        assertEquals(2.0, r.position, 0.0)
    }

    @Test fun reverseCanReturnFromSeekBeyondDecodedAudio() {
        val r = PcmRenderer().apply { position = 8.0; velocity = -2f }
        val (out, end) = r.block(stereo(100, 200, 300, 400), n = 3, target = -2f, scratch = true, done = false)
        assertArrayEquals(stereo(0, 0, 300), out)
        assertFalse(end)
    }

    @Test fun mutePreservesPlaybackTiming() {
        val r = PcmRenderer().apply { velocity = 1f }
        val (out, _) = r.block(stereo(100, 200, 300, 400), n = 2, muted = true)
        assertArrayEquals(ShortArray(4), out)
        assertEquals(2.0, r.position, 0.0)
    }

    @Test fun settledPauseOutputsSilenceAndDoesNotAdvance() {
        val r = PcmRenderer().apply { position = 1.0 }
        val (out, _) = r.block(stereo(100, 200, 300), n = 128, target = 0f)
        assertArrayEquals(ShortArray(256), out)
        assertEquals(1.0, r.position, 0.0)
        assertEquals(0f, r.velocity, 0f)
    }

    @Test fun emptyAndPartiallyAllocatedBuffersAreSafe() {
        val r = PcmRenderer()
        assertArrayEquals(ShortArray(8), r.block(shortArrayOf(), n = 4, done = false).first)
        assertFalse(r.block(shortArrayOf(), n = 1, done = false).second)
        assertTrue(r.block(shortArrayOf(), n = 1).second)
        assertTrue(r.block(stereo(100), frames = 100, n = 4).second)
    }

    @Test fun pauseBrakingConvergesToSleepThreshold() {
        val r = PcmRenderer().apply { velocity = 1f }
        val pcm = stereo(*IntArray(44100) { (it % 3000) + 1 })
        val out = ShortArray(4096)
        repeat(40) { r.render(pcm, 2048, out, false, 0f, false, 44100, false, .01f, .001f, .0001f) }
        assertEquals(0f, r.velocity, 0f)
        assertArrayEquals(ShortArray(4096), out)
    }
}

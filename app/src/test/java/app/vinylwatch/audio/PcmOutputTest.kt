package app.vinylwatch.audio

import org.junit.Assert.*
import org.junit.Test

class PcmOutputTest {
    @Test fun shortWritesDeliverRemainingSamplesWithoutSkippingOrDuplicating() {
        val buffer = ShortArray(16) { (it * 100).toShort() }
        val received = mutableListOf<Short>()
        assertTrue(PcmOutput.writeFully(buffer, buffer.size, { true }) { offset, count ->
            val n = minOf(count, 6)
            received.addAll(buffer.slice(offset until offset + n))
            n
        })
        assertArrayEquals(buffer, received.toShortArray())
    }

    @Test fun stoppingInterruptsWriteLoop() {
        var running = true
        var calls = 0
        assertFalse(PcmOutput.writeFully(ShortArray(16), 16, { running }) { _, _ ->
            calls++; running = false; 4
        })
        assertEquals(1, calls)
    }

    @Test(expected = IllegalStateException::class)
    fun zeroWriteFailsInsteadOfSpinningAtFullCpu() {
        PcmOutput.writeFully(ShortArray(8), 8, { true }) { _, _ -> 0 }
    }

    @Test(expected = IllegalStateException::class)
    fun deadAudioDeviceIsReported() {
        PcmOutput.writeFully(ShortArray(8), 8, { true }) { _, _ -> -6 }
    }

    @Test(expected = IllegalStateException::class)
    fun partialStereoFrameIsRejected() {
        PcmOutput.writeFully(ShortArray(8), 8, { true }) { _, _ -> 3 }
    }
}

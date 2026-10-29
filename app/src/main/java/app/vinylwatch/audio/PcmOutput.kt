package app.vinylwatch.audio

/** AudioTrack вправе принять только часть блока. Счётчик позиции обновляют после полной записи. */
internal object PcmOutput {
    inline fun writeFully(
        buffer: ShortArray, count: Int, running: () -> Boolean, write: (Int, Int) -> Int,
    ): Boolean {
        require(count in 0..buffer.size && count % 2 == 0)
        var offset = 0
        while (offset < count && running()) {
            val written = write(offset, count - offset)
            check(written > 0 && written <= count - offset && written % 2 == 0) {
                "AudioTrack.write = $written"
            }
            offset += written
        }
        return offset == count
    }
}

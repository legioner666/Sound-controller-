package com.soniccontrol.core.protocol

/**
 * Accumulates bytes from a stream transport (RFCOMM) or fragmented notifications and cuts
 * complete frames with a protocol-specific [splitter]. Stale partial data is dropped after [maxAgeMillis].
 */
class StreamBuffer(
    private val maxAgeMillis: Long = 3000,
    private val maxBytes: Int = 64 * 1024,
    private val splitter: (ByteArray) -> Pair<List<ByteArray>, Int>,
) {
    private var buffer = ByteArray(0)
    private var lastAt = 0L

    fun feed(chunk: ByteArray, now: Long): List<ByteArray> {
        if (buffer.isNotEmpty() && now - lastAt > maxAgeMillis) buffer = ByteArray(0)
        lastAt = now
        buffer += chunk
        if (buffer.size > maxBytes) buffer = ByteArray(0)
        val (frames, consumed) = splitter(buffer)
        buffer = buffer.copyOfRange(consumed.coerceIn(0, buffer.size), buffer.size)
        return frames
    }

    fun pending(): Int = buffer.size
}

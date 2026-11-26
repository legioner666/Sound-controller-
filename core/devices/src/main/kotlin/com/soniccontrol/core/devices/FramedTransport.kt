package com.soniccontrol.core.devices

import com.soniccontrol.core.protocol.StreamBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wraps a raw transport and re-emits complete protocol frames, cut by [splitter]
 * (returns frames and the number of bytes consumed).
 */
class FramedTransport(
    private val inner: BluetoothTransport,
    scope: CoroutineScope,
    private val clock: () -> Long,
    splitter: (ByteArray) -> Pair<List<ByteArray>, Int>,
) : BluetoothTransport {
    private val buffer = StreamBuffer(splitter = splitter)
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    // The reader ends with the inner transport (TASK 010): a socket the headset closed no longer keeps a coroutine alive.
    private val job: Job = scope.launch {
        val reader = launch { inner.incoming.collect { chunk -> buffer.feed(chunk, clock()).forEach { frames.emit(it) } } }
        inner.state.first { it.isEnded }
        reader.cancel()
    }

    override val description: String get() = inner.description
    override val state: StateFlow<TransportState> get() = inner.state
    override val incoming: SharedFlow<ByteArray> get() = frames
    override val canWrite: Boolean get() = inner.canWrite

    override suspend fun close() {
        job.cancel()
        inner.close()
    }

    override suspend fun write(bytes: ByteArray) = inner.write(bytes)
}

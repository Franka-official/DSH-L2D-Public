package com.prts.client.audio

import com.prts.client.net.InAudio
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Same-process audio ownership. Never serialized onto the WebSocket.
 * The receiver must close after completion, error, cancellation or rejection.
 */
class LocalSpeechAudio(
    val message: InAudio,
    val wav: File,
    private val release: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    val isClosed: Boolean get() = released.get()
    override fun close() { if (released.compareAndSet(false, true)) release() }
}

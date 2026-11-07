package com.prts.client.localdsh

import com.prts.client.net.FaceObservationMetadata
import com.prts.client.net.Proto
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class LocalDshClient(private val token: String, private val event: (String) -> Unit, private val state: (String) -> Unit) {
    private val identity = Any()
    @Volatile private var opened = false
    private val micEpoch = AtomicLong()
    fun open() {
        if (opened) return
        if (!LocalDshBridge.frontendAuthorized(token)) { state("unauthorized"); return }
        opened = true; LocalDshHub.attach(identity, event, state)
    }
    fun send(raw: String): Boolean {
        if (!opened) return false
        return runCatching {
            if (JSONObject(raw).optString("type") in setOf("interrupt-signal", "text-input", "control")) micEpoch.incrementAndGet()
            LocalDshHub.fromFrontend(identity, raw)
        }.getOrDefault(false)
    }
    fun beginMic(): Long = if (opened) micEpoch.incrementAndGet() else -1L
    fun micCurrent(ticket: Long): Boolean = opened && ticket >= 0 && micEpoch.get() == ticket
    fun transcribe(base64: String, samples: Int, language: String, ticket: Long,
                   face: FaceObservationMetadata?): Boolean =
        LocalDshHub.transcribe(identity, base64, samples, language, face?.let {
            JSONObject(Proto.json.encodeToString(FaceObservationMetadata.serializer(), it))
        }) { micCurrent(ticket) }
    fun close() { micEpoch.incrementAndGet(); if (opened) { opened = false; LocalDshHub.detach(identity) } }
}

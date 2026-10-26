package com.example.m4control.gaia

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

sealed interface Reply {
    class Ok(val payload: ByteArray) : Reply
    class Rejected(val code: Int?) : Reply
    data object Timeout : Reply
}

class SessionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One control connection: a continuous receive loop plus serialized request/response correlation.
 * Responses must match vendor and base command; notifications are routed while a request
 * is outstanding. After a timeout the command's slot is "poisoned" until its late response arrives;
 * reusing a poisoned slot ends the session instead of risking a mismatched response.
 */
class GaiaSession(
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onNotification: (Frame) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onClosed: (Throwable?) -> Unit = {},
) {
    private class Waiting(val vendor: Int, val base: Int, val reply: CompletableDeferred<Frame>)

    private val parser = FrameParser()
    private val requestLock = Mutex()
    private val lock = Any()
    private var waiting: Waiting? = null
    private val timedOut = mutableSetOf<Int>()
    private var reader: Job? = null

    @Volatile
    var isClosed = false
        private set

    fun start() {
        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) throw EOFException("control connection closed by peer")
                    val frames = parser.feed(buf.copyOf(n))
                    parser.rejected.forEach { onLog("!! rejected $it") }
                    parser.rejected.clear()
                    frames.forEach(::route)
                }
            } catch (t: Throwable) {
                close(t)
            }
        }
    }

    private fun route(f: Frame) {
        onLog("<< ${f.hex()}")
        when (f.type) {
            Frame.TYPE_RESPONSE, Frame.TYPE_ERROR -> {
                val w = synchronized(lock) { waiting }
                if (w != null && f.vendor == w.vendor && f.base == w.base) {
                    w.reply.complete(f)
                } else if (synchronized(lock) { timedOut.remove(f.base) }) {
                    onLog("   late response for %04X dropped; slot free again".format(f.base))
                } else {
                    onLog("   unmatched response %04X/%04X".format(f.vendor, f.command))
                }
            }
            Frame.TYPE_NOTIFICATION -> onNotification(f)
            else -> onLog("   unexpected command frame from device")
        }
    }

    suspend fun request(
        base: Int,
        payload: IntArray = IntArray(0),
        timeoutMs: Long = 1500,
        vendor: Int = Frame.VENDOR_SENNHEISER,
    ): Reply = requestLock.withLock {
        if (isClosed) throw SessionClosedException("session closed")
        if (synchronized(lock) { base in timedOut }) {
            val e = SessionClosedException("late response pending for %04X; ending session".format(base))
            close(e)
            throw e
        }
        val reply = CompletableDeferred<Frame>()
        synchronized(lock) { waiting = Waiting(vendor, base, reply) }
        val frame = Frame(vendor, base, ByteArray(payload.size) { payload[it].toByte() })
        try {
            withContext(Dispatchers.IO) {
                output.write(frame.encode())
                output.flush()
            }
            onLog(">> ${frame.hex()}")
            val f = withTimeoutOrNull(timeoutMs) { reply.await() }
            if (f == null) {
                synchronized(lock) { timedOut += base }
                onLog("   timeout for %04X".format(base))
                Reply.Timeout
            } else if (f.type == Frame.TYPE_ERROR) {
                Reply.Rejected(Codec.u8(f.payload))
            } else {
                Reply.Ok(f.payload)
            }
        } catch (e: IOException) {
            close(e)
            throw SessionClosedException("write failed", e)
        } finally {
            synchronized(lock) { waiting = null }
        }
    }

    fun close(cause: Throwable? = null) {
        if (isClosed) return
        isClosed = true
        runCatching { input.close() }
        runCatching { output.close() }
        synchronized(lock) { waiting }?.reply?.completeExceptionally(SessionClosedException("session closed", cause))
        reader?.cancel()
        onClosed(cause)
    }
}

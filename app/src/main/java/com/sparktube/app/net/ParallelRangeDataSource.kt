package com.sparktube.app.net

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Multi-threaded [DataSource] for progressive media streams.
 *
 * WHY: a single HTTP connection to a large 720p/1080p video ramps up too
 * slowly to fill the startup playback buffer, so big videos sat on the
 * loading spinner and tripped the "Slow connection" watchdog even on a
 * perfectly stable network, while small videos started instantly.
 * DownloadCenter already proves YouTube (googlevideo) URLs happily serve
 * parallel HTTP Range requests — it pulls files with 6 of them. The player
 * now uses the same trick: when ExoPlayer opens a stream, this source
 * splits the remaining bytes into ~1 MiB chunks and fetches several of
 * them AT THE SAME TIME over separate connections, handing the bytes to
 * the player in order. Chunks are published while still downloading
 * (partial data is served immediately), so the first frame does NOT wait
 * for a whole chunk — it appears as soon as the first bytes arrive, and
 * everything after it fills ~parallelism-times faster.
 *
 * Safety rails:
 *  - tiny or non-HTTP requests go straight through on a single connection;
 *  - if a server ignores or mismatches Range headers, or a chunk transfer
 *    fails mid-stream, the source degrades to ONE plain connection at the
 *    exact byte the player is waiting for — bytes are never skipped,
 *    duplicated or corrupted;
 *  - read-ahead is capped (window) so memory stays bounded, and ExoPlayer
 *    throttles demand through its own buffer limits anyway;
 *  - close() / a seek re-open tears everything down; a fresh open() starts
 *    a fresh parallel session at the new position.
 */
@OptIn(UnstableApi::class)
class ParallelRangeDataSource private constructor(
    private val upstreamFactory: DataSource.Factory,
    private val parallelism: Int,
    private val chunkSize: Long,
) : DataSource {

    /**
     * Wraps any upstream [DataSource.Factory]. The upstream chain (for this
     * app: ResolvingDataSource -> DefaultHttpDataSource) is used per chunk
     * connection, so URI resolution, redirects and headers keep working
     * exactly as before.
     */
    @OptIn(UnstableApi::class)
    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val parallelism: Int = DEFAULT_PARALLELISM,
        private val chunkSize: Long = DEFAULT_CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): ParallelRangeDataSource =
            ParallelRangeDataSource(upstreamFactory, parallelism, chunkSize)
    }

    /** One in-flight or completed chunk. Safe to touch only under [lock]. */
    private class ChunkBuffer {
        var bytes: ByteArray? = null
        var filled = 0
        var connected = false
        var complete = false
        var lastChunk = false
        var error: IOException? = null
    }

    private inner class Worker(private val mySession: Int) : Runnable {
        override fun run() {
            var counted = false
            try {
                synchronized(lock) {
                    if (mySession != sessionId) return
                    liveWorkers++
                    counted = true
                }
                while (true) {
                    var index: Long = -1
                    synchronized(lock) {
                        while (index < 0) {
                            if (closed || degraded || eofFound || mySession != sessionId) return
                            // For sparktube:// queue URIs the upstream
                            // resolver does network work per open; letting
                            // workers 1..n race it would resolve the SAME
                            // song several times. So they wait until chunk
                            // 0's connection (and its resolve) is up.
                            if (nextAssign > 0 && staggerAfterChunk0) {
                                val cb0 = chunks[0L]
                                val gateOpen = cb0 == null ||
                                    cb0.connected || cb0.error != null || cb0.complete
                                if (!gateOpen) {
                                    lock.wait()
                                    continue
                                }
                            }
                            if (nextAssign >= readIndex + windowChunks) {
                                lock.wait()
                                continue
                            }
                            index = nextAssign++
                            chunks[index] = ChunkBuffer()
                        }
                    }
                    fetch(index, mySession)
                }
            } catch (_: InterruptedException) {
                // close()/degrade interrupted a wait — just exit; the chunk
                // we own resolves on its own or is ignored.
            } finally {
                synchronized(lock) {
                    if (counted) liveWorkers--
                    lock.notifyAll()
                }
            }
        }
    }

    init {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(chunkSize in 1..(32L * 1024 * 1024)) { "chunkSize out of range" }
    }

    private val windowChunks: Int =
        maxOf(parallelism + 2, (MAX_WINDOW_BYTES / chunkSize).toInt())

    // ---- session state (guarded by [lock] unless noted) ----
    private val lock = Any()
    private val transferListeners = ArrayList<TransferListener>()

    private var openedSpec: DataSpec? = null
    private var streamStart = 0L
    private var streamEnd = -1L          // exclusive absolute end; -1 = unknown
    private var sessionId = 0
    private var staggerAfterChunk0 = false

    private var passthrough: DataSource? = null   // single-connection mode
    private var single: DataSource? = null        // degraded single connection
    private var degraded = false
    private var closed = false
    private var eof = false
    private var eofFound = false

    private var executor: ExecutorService? = null
    private val chunks = HashMap<Long, ChunkBuffer>()
    private var nextAssign = 0L
    private var readIndex = 0L
    private var readOff = 0
    private var liveWorkers = 0

    @Volatile private var connectedUri: Uri? = null
    @Volatile private var lastHeaders: Map<String, List<String>> = emptyMap()

    // ---- DataSource lifecycle ----

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        reset()
        openedSpec = dataSpec
        val scheme = dataSpec.uri.scheme
        val eligible = scheme == "http" || scheme == "https" || scheme == "sparktube"
        val small = dataSpec.length != C.LENGTH_UNSET.toLong() &&
            dataSpec.length <= chunkSize
        if (!eligible || small || dataSpec.uriPositionOverride != null) {
            val ds = createUpstream()
            passthrough = ds
            return ds.open(dataSpec)
        }

        streamStart = dataSpec.position
        streamEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.position + dataSpec.length
        } else {
            -1L
        }
        staggerAfterChunk0 = scheme == "sparktube"

        val ex = Executors.newFixedThreadPool(parallelism) { r ->
            Thread(r, "$TAG-${SEQ.incrementAndGet()}")
        }
        val session = synchronized(lock) {
            sessionId++
            executor = ex
            sessionId
        }
        repeat(parallelism) { ex.execute(Worker(session)) }

        // Wait for chunk 0's connection to be established: it gives us the
        // stream length via Content-Range (for the open() return value) and
        // an early chance to fall back to a single connection if it fails.
        // The deadline is a pure safety valve — workers normally grab chunk
        // 0 within milliseconds.
        val deadline = System.nanoTime() + OPEN_CHUNK0_TIMEOUT_NANOS
        synchronized(lock) {
            while (true) {
                if (closed) break
                val cb0 = chunks[0L]
                if (cb0 != null && (cb0.connected || cb0.error != null || cb0.complete)) break
                if (System.nanoTime() >= deadline) break
                lock.wait(100)
            }
        }
        val cb0 = synchronized(lock) { chunks[0L] }
        if (cb0?.error != null || (cb0 == null && !closed)) {
            // The parallel start failed outright (or workers died before
            // even starting) — replay a plain single connection instead.
            return degradeToSingle(streamStart)
        }
        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            streamEndNow() >= 0 -> streamEndNow() - streamStart
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed) throw IOException("Stream closed")
            passthrough?.let { return it.read(buffer, offset, length) }
            if (eof) return C.RESULT_END_OF_INPUT
            if (degraded) {
                val s = single ?: throw IOException("Recovered stream unavailable")
                return s.read(buffer, offset, length)
            }

            var degradePos: Long? = null
            var out: Int? = null
            synchronized(lock) {
                if (!closed && !degraded) {
                    val cb = chunks[readIndex]
                    when {
                        cb == null -> {
                            // Should not happen (readIndex never runs ahead
                            // of assignment) — recover rather than hang.
                            if (liveWorkers <= 0) degradePos = streamPos(readIndex, readOff)
                            else lock.wait(250)
                        }
                        cb.filled > readOff -> {
                            val b = cb.bytes!!
                            val n = minOf(length, cb.filled - readOff)
                            System.arraycopy(b, readOff, buffer, offset, n)
                            readOff += n
                            if (readOff >= cb.filled && cb.complete) {
                                if (cb.lastChunk) {
                                    eof = true
                                } else {
                                    chunks.remove(readIndex)
                                    readIndex++
                                    readOff = 0
                                    lock.notifyAll()   // window advanced
                                }
                            }
                            out = n
                        }
                        cb.error != null -> degradePos = streamPos(readIndex, readOff)
                        cb.lastChunk -> eof = true
                        cb.complete -> {
                            chunks.remove(readIndex)
                            readIndex++
                            readOff = 0
                            lock.notifyAll()
                        }
                        else -> {
                            if (liveWorkers <= 0) degradePos = streamPos(readIndex, readOff)
                            else lock.wait(250)
                        }
                    }
                }
            }
            val dp: Long? = degradePos
            if (dp != null) {
                degradeToSingle(dp)
                continue
            }
            val res: Int? = out
            if (res != null) return res
        }
    }

    override fun getUri(): Uri? =
        passthrough?.uri ?: single?.uri ?: connectedUri ?: openedSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        passthrough?.responseHeaders ?: single?.responseHeaders ?: lastHeaders

    override fun addTransferListener(transferListener: TransferListener) {
        synchronized(transferListeners) {
            transferListeners.add(transferListener)
            passthrough?.addTransferListener(transferListener)
            single?.addTransferListener(transferListener)
        }
    }

    @Throws(IOException::class)
    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        executor?.let { it.shutdownNow() }
        val closables = ArrayList<DataSource>(2)
        passthrough?.let { closables.add(it) }
        single?.let { closables.add(it) }
        passthrough = null
        single = null
        closables.forEach { ds ->
            try {
                ds.close()
            } catch (_: IOException) {
            }
        }
        // Workers close their own chunk connections in their finally blocks.
    }

    // ---- internals ----

    private fun reset() {
        executor?.let { it.shutdownNow() }
        synchronized(lock) {
            closed = false
            degraded = false
            eof = false
            eofFound = false
            chunks.clear()
            nextAssign = 0
            readIndex = 0
            readOff = 0
            streamStart = 0
            streamEnd = -1
        }
        executor = null
        passthrough = null
        single = null
        openedSpec = null
        connectedUri = null
        lastHeaders = emptyMap()
    }

    private fun createUpstream(): DataSource {
        val ds = upstreamFactory.createDataSource()
        synchronized(transferListeners) {
            transferListeners.forEach { ds.addTransferListener(it) }
        }
        return ds
    }

    private fun streamEndNow(): Long = synchronized(lock) { streamEnd }

    private fun streamPos(index: Long, off: Int): Long = streamStart + index * chunkSize + off

    private fun chunkLengthFor(index: Long): Long {
        val start = streamStart + index * chunkSize
        val end = streamEndNow()
        return if (end >= 0) minOf(chunkSize, end - start) else chunkSize
    }

    private fun fetch(index: Long, session: Int) {
        val cb = synchronized(lock) { chunks[index] } ?: return
        var upstream: DataSource? = null
        try {
            if (session != sessionId) return
            val spec = openedSpec ?: return
            val start = streamStart + index * chunkSize
            val len = chunkLengthFor(index)
            if (len <= 0) {
                synchronized(lock) {
                    cb.complete = true
                    cb.lastChunk = true
                    eofFound = true
                    lock.notifyAll()
                }
                return
            }
            val chunkSpec = spec.buildUpon().setPosition(start).setLength(len).build()
            val ds = createUpstream()
            upstream = ds
            ds.open(chunkSpec)
            connectedUri = ds.uri
            lastHeaders = ds.responseHeaders

            // Verify the server actually honored the Range request; a 200
            // full-body reply would hand us bytes from 0 for a chunk in the
            // middle of the stream and silently corrupt playback.
            val contentRange = ds.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
            if (contentRange != null) {
                val m = CONTENT_RANGE.matcher(contentRange)
                if (m.matches()) {
                    val servedFrom = m.group(1)!!.toLong()
                    if (servedFrom != start) {
                        throw IOException("Range mismatch: asked $start, served $servedFrom")
                    }
                    val total = m.group(3)?.let { if (it == "*") null else it.toLong() }
                    if (total != null && total > 0) synchronized(lock) {
                        streamEnd = if (streamEnd < 0) total else minOf(streamEnd, total)
                        lock.notifyAll()
                    }
                }
            } else if (start != 0L) {
                throw IOException("Server ignored Range request (no Content-Range)")
            }

            synchronized(lock) {
                cb.connected = true
                lock.notifyAll()
            }

            val buf = ByteArray(len.toInt())
            var filled = 0
            while (filled < len) {
                val n = ds.read(buf, filled, (len - filled).toInt())
                if (n == C.RESULT_END_OF_INPUT || n < 0) break
                if (n > 0) {
                    filled += n
                    synchronized(lock) {
                        cb.bytes = buf
                        cb.filled = filled
                        lock.notifyAll()
                    }
                }
            }
            synchronized(lock) {
                cb.bytes = buf
                cb.filled = filled
                cb.complete = true
                val hitEnd = filled < len ||
                    (streamEnd >= 0 && start + filled >= streamEnd)
                cb.lastChunk = hitEnd
                if (hitEnd) eofFound = true
                lock.notifyAll()
            }
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            val chunkStart = streamStart + index * chunkSize
            val beyondEnd = streamEndNow() < 0 || chunkStart >= streamEndNow()
            if (e.responseCode == 416 && beyondEnd) {
                // "Range not satisfiable": we ran past the end of the
                // resource (length was unknown) — that is our EOF signal.
                synchronized(lock) {
                    cb.complete = true
                    cb.lastChunk = true
                    eofFound = true
                    lock.notifyAll()
                }
            } else {
                markFailed(cb, e)
            }
        } catch (e: IOException) {
            markFailed(cb, e)
        } catch (t: Throwable) {
            markFailed(cb, IOException("Chunk fetch failed: ${t.message ?: t.javaClass.simpleName}", t))
        } finally {
            try {
                upstream?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun markFailed(cb: ChunkBuffer, e: IOException) {
        Log.w(TAG, "Chunk fetch failed: ${e.message}")
        synchronized(lock) {
            cb.error = e
            cb.complete = true
            lock.notifyAll()
        }
    }

    /**
     * Abandons parallel fetching and continues the stream over ONE plain
     * connection opened at [pos] (the exact byte the player still needs).
     * Returns the single connection's open() length (or 0 if nothing is
     * left to read). Idempotent-ish: only the loader thread ever calls it.
     */
    @Throws(IOException::class)
    private fun degradeToSingle(pos: Long): Long {
        synchronized(lock) {
            if (degraded) return C.LENGTH_UNSET.toLong()
            degraded = true
            eofFound = true   // stop workers from taking new chunks
            lock.notifyAll()
        }
        executor?.let { it.shutdownNow() }
        val spec = openedSpec ?: return C.LENGTH_UNSET.toLong()
        val remaining = when {
            spec.length != C.LENGTH_UNSET.toLong() -> spec.position + spec.length - pos
            streamEndNow() >= 0 -> streamEndNow() - pos
            else -> C.LENGTH_UNSET.toLong()
        }
        if (remaining != C.LENGTH_UNSET.toLong() && remaining <= 0) {
            synchronized(lock) { eof = true }
            return 0
        }
        Log.w(TAG, "Falling back to a single connection at byte $pos")
        val ds = createUpstream()
        val result = ds.open(spec.buildUpon().setPosition(pos).setLength(remaining).build())
        single = ds
        return result
    }

    companion object {
        private const val TAG = "ParallelRangeDS"

        /** Parallel chunk connections per stream. */
        private const val DEFAULT_PARALLELISM = 4

        /** Chunk size for ranged requests. */
        private const val DEFAULT_CHUNK_BYTES = 1024L * 1024L

        /**
         * Read-ahead ceiling. ExoPlayer stops pulling once its own buffer
         * limit is reached, so this only bounds the video stream's burst
         * (audio buffers are a couple of MB at most).
         */
        private const val MAX_WINDOW_BYTES = 16L * 1024 * 1024

        private val CONTENT_RANGE: Pattern =
            Pattern.compile("^bytes (\\d+)-(\\d+)/(\\*|\\d+)$", Pattern.CASE_INSENSITIVE)

        /** Safety valve for open()'s wait on chunk 0's connection. */
        private const val OPEN_CHUNK0_TIMEOUT_NANOS = 2_000_000_000L

        private val SEQ = AtomicInteger(0)
    }
}

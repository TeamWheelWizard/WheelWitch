package com.skiletro.wheelwitch.util.io

import com.skiletro.wheelwitch.util.net.HttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/** Progress snapshot for an in-flight download, emitted to UI consumers. */
data class DownloadProgress(
    val progress: Float,
    val bytesPerSecond: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
)

/**
 * Progress snapshot for a parallel chunked download, emitted to UI consumers.
 *
 * Structurally identical to [DownloadProgress] with one extra field
 * ([activeChunks]) so the UI can show a "X of N chunks" indicator if
 * it wants. Fields are an aggregate across the worker pool.
 */
data class ParallelDownloadProgress(
    val progress: Float,
    val bytesPerSecond: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val activeChunks: Int,
)

/** Shared HTTP download utility used by both [com.skiletro.wheelwitch.domain.RewindPackManager] and [com.skiletro.wheelwitch.util.mii.MiiWadInstaller]. */
object FileDownloader {
    private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
    private const val TAG = "FileDownloader"

    /**
     * Default chunk count for [downloadInParallel]. Four is conservative
     * enough to sit under any CDN's per-host connection cap while still
     * letting a single stream saturate a typical home broadband link.
     */
    const val DEFAULT_PARALLELISM: Int = 4

    /** Tick interval (ms) for the aggregate progress emitter in [downloadInParallel]. */
    private const val PROGRESS_TICK_MILLIS: Long = 200L

    /**
     * Downloads [url] to [targetFile]. Blocking - call from a background dispatcher.
     *
     * On transient failures (network [IOException] or HTTP 5xx), retries up to [maxRetries] times
     * with exponential backoff ([initialBackoffMillis] * 2^attempt). HTTP 4xx and other non-2xx
     * responses are treated as deterministic failures and are not retried. Throws on exhausted
     * retries, on a 4xx, or on a missing/empty body.
     *
     * @param onProgress Optional callback with progress and instantaneous byte rate. Throttled to
     *   ≥1% progress changes; always emits progress=1f at the end of a successful download. Reset
     *   to bytesPerSecond=0 at the start of each retry attempt so the UI does not show a stale rate
     *   during backoff.
     * @param client OkHttp client to use. Defaults to [HttpClientProvider.client];
     *   pass [HttpClientProvider.largeDownloadClient] for big payloads to
     *   avoid spurious 15s read timeouts.
     * @param maxRetries Number of retries after the first attempt (so 2 = up to 3 total attempts).
     * @param initialBackoffMillis Base backoff in ms; the delay before retry `n` is
     *   `initialBackoffMillis * 2^n` (1s, 2s, 4s, …).
     */
    fun downloadToFile(
        url: String,
        targetFile: File,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        client: OkHttpClient = HttpClientProvider.client,
        maxRetries: Int = 2,
        initialBackoffMillis: Long = 1000L,
    ): File {
        require(maxRetries >= 0) { "maxRetries must be >= 0" }
        val totalAttempts = maxRetries + 1
        var lastError: Throwable? = null

        for (attempt in 0 until totalAttempts) {
            onProgress?.invoke(
                DownloadProgress(
                    progress = 0f,
                    bytesPerSecond = 0L,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                )
            )
            try {
                return downloadOnce(url, targetFile, onProgress, client)
            } catch (e: Http4xxException) {
                Timber.tag("FileDownloader").w(e, "HTTP 4xx for %s; not retrying", url)
                throw e
            } catch (e: EmptyBodyException) {
                Timber.tag("FileDownloader").w(e, "Empty body for %s; not retrying", url)
                throw e
            } catch (e: Http5xxException) {
                Timber.tag("FileDownloader")
                    .w(e, "HTTP 5xx on attempt %d/%d for %s", attempt + 1, totalAttempts, url)
                lastError = e
            } catch (e: IOException) {
                Timber.tag("FileDownloader")
                    .w(e, "Transient network failure on attempt %d/%d for %s", attempt + 1, totalAttempts, url)
                lastError = e
            }

            if (attempt < totalAttempts - 1) {
                val backoff = initialBackoffMillis * (1L shl attempt)
                try {
                    Thread.sleep(backoff)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Download interrupted during backoff", ie)
                }
            }
        }

        Timber.tag("FileDownloader")
            .e(lastError, "Download failed after %d attempts: %s", totalAttempts, url)
        throw IOException(
            "Download failed after $totalAttempts attempts: ${url}",
            lastError,
        )
    }

    /**
     * Downloads [url] to [targetFile] in [parallelism] concurrent byte
     * ranges. Blocking - call from a background dispatcher.
     *
     * Probes with HEAD first; if the server doesn't advertise
     * `Accept-Ranges: bytes` or doesn't return a `Content-Length`, falls
     * back to [downloadToFile] with the same retry / progress semantics.
     * The Cloudflare-fronted update server in production supports
     * ranges, so the parallel path is the common one.
     *
     * The destination file is pre-allocated to the final size before any
     * chunk writes, so the OS pre-reserves disk space and the consumer
     * ([com.skiletro.wheelwitch.data.DolphinTree.extractZipToPack])
     * always sees a complete-length file.
     *
     * Per-chunk retry on transient errors (network IOException or HTTP
     * 5xx) uses the same exponential backoff as [downloadToFile]. If any
     * chunk exhausts its retries, the worker pool is cancelled, the
     * partial file is deleted, and an IOException surfaces to the caller.
     *
     * @param parallelism Number of concurrent byte-range fetches. Defaults
     *   to [DEFAULT_PARALLELISM] (4). Capped at 16 to keep the dispatcher
     *   clone reasonable. The per-call OkHttp dispatcher is built with
     *   `maxRequests = parallelism + 4` and `maxRequestsPerHost =
     *   parallelism` so two concurrent downloads don't fight over a
     *   shared dispatcher.
     * @param onProgress Aggregate progress callback. Emitted at most
     *   once per [PROGRESS_TICK_MILLIS] interval and at 100% on success.
     *   Reset to `bytesPerSecond = 0` at the start so a stale rate from
     *   a prior download doesn't bleed in.
     */
    fun downloadInParallel(
        url: String,
        targetFile: File,
        parallelism: Int = DEFAULT_PARALLELISM,
        onProgress: ((ParallelDownloadProgress) -> Unit)? = null,
        client: OkHttpClient = HttpClientProvider.largeDownloadClient,
        maxRetries: Int = 2,
        initialBackoffMillis: Long = 1000L,
    ): File = runBlocking {
        downloadInParallelInternal(
            url = url,
            targetFile = targetFile,
            parallelism = parallelism,
            onProgress = onProgress,
            client = client,
            maxRetries = maxRetries,
            initialBackoffMillis = initialBackoffMillis,
        )
    }

    private suspend fun downloadInParallelInternal(
        url: String,
        targetFile: File,
        parallelism: Int,
        onProgress: ((ParallelDownloadProgress) -> Unit)?,
        client: OkHttpClient,
        maxRetries: Int,
        initialBackoffMillis: Long,
    ): File = withContext(Dispatchers.IO) {
        require(parallelism in 1..16) { "parallelism must be in 1..16" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }

        val (totalBytes, rangesSupported) = probeRanges(url, client)
        if (!rangesSupported || totalBytes <= 0L) {
            Timber.tag(TAG).d(
                "downloadInParallel: server doesn't advertise ranges or lacks Content-Length; " +
                    "falling back to single-stream downloadToFile",
            )
            downloadToFile(
                url = url,
                targetFile = targetFile,
                onProgress = { dp ->
                    onProgress?.invoke(
                        ParallelDownloadProgress(
                            progress = dp.progress,
                            bytesPerSecond = dp.bytesPerSecond,
                            bytesDownloaded = dp.bytesDownloaded,
                            totalBytes = dp.totalBytes,
                            activeChunks = 1,
                        )
                    )
                },
                client = client,
                maxRetries = maxRetries,
                initialBackoffMillis = initialBackoffMillis,
            )
            return@withContext targetFile
        }

        // Build a per-call dispatcher clone so concurrent downloads
        // don't fight over the same client.
        val parallelClient =
            client.newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = parallelism + 4
                        maxRequestsPerHost = parallelism
                    }
                )
                .build()

        val ranges = computeRanges(totalBytes, parallelism)
        val activeChunks = AtomicLong(ranges.size.toLong())
        val bytesDone = AtomicLong(0L)

        // Pre-allocate the file so the OS pre-reserves space and
        // downstream consumers (e.g. ZipFile opening the cached pack)
        // always see a complete-length file.
        RandomAccessFile(targetFile, "rw").use { it.setLength(totalBytes) }

        try {
            runParallelDownload(
                url = url,
                targetFile = targetFile,
                ranges = ranges,
                totalBytes = totalBytes,
                bytesDone = bytesDone,
                activeChunks = activeChunks,
                onProgress = onProgress,
                client = parallelClient,
                maxRetries = maxRetries,
                initialBackoffMillis = initialBackoffMillis,
            )
        } catch (t: Throwable) {
            targetFile.delete()
            throw t
        }

        if (targetFile.length() != totalBytes) {
            targetFile.delete()
            throw IOException(
                "Parallel download produced ${targetFile.length()} bytes, expected $totalBytes",
            )
        }
        targetFile
    }

    /**
     * Probes the server with HEAD. Returns `(contentLength, rangesSupported)`.
     * A `null` Content-Length or missing `Accept-Ranges: bytes` is
     * treated as "ranges not supported"; the caller falls back to
     * single-stream.
     *
     * The HEAD is a single round trip paid up front; the alternative
     * (speculatively dispatch the first chunk and trust the response
     * `Content-Range` for total bytes) was rejected because the
     * pre-allocation invariant at line 268 (`file.length() ==
     * totalBytes`) requires the final size up front. The 100 ms
     * RTT cost of the HEAD is dwarfed by the multi-RTT parallel
     * download, so the serial ordering stays.
     */
    private fun probeRanges(url: String, client: OkHttpClient): Pair<Long, Boolean> {
        val request = Request.Builder().url(url).head().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L to false
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                if (contentLength <= 0L) return 0L to false
                val acceptRanges = response.header("Accept-Ranges")
                val supports = acceptRanges?.equals("bytes", ignoreCase = true) == true
                contentLength to supports
            }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "HEAD probe failed for %s", url)
            0L to false
        }
    }

    private data class ByteRange(val start: Long, val endInclusive: Long) {
        val size: Long
            get() = endInclusive - start + 1L
    }

    private fun computeRanges(totalBytes: Long, parallelism: Int): List<ByteRange> {
        val chunkSize = totalBytes / parallelism
        val ranges = ArrayList<ByteRange>(parallelism)
        var cursor = 0L
        for (i in 0 until parallelism) {
            val endInclusive =
                if (i == parallelism - 1) totalBytes - 1
                else cursor + chunkSize - 1
            ranges.add(ByteRange(cursor, endInclusive))
            cursor = endInclusive + 1
        }
        return ranges
    }

    /**
     * Runs N chunk workers in parallel, an aggregate progress emitter,
     * and a per-chunk retry layer. Suspends until all chunks succeed or
     * any chunk exhausts its retries.
     */
    private suspend fun runParallelDownload(
        url: String,
        targetFile: File,
        ranges: List<ByteRange>,
        totalBytes: Long,
        bytesDone: AtomicLong,
        activeChunks: AtomicLong,
        onProgress: ((ParallelDownloadProgress) -> Unit)?,
        client: OkHttpClient,
        maxRetries: Int,
        initialBackoffMillis: Long,
    ) {
        coroutineScope {
            val progressJob =
                async(Dispatchers.Default) {
                    emitProgressLoop(
                        totalBytes = totalBytes,
                        bytesDone = bytesDone,
                        activeChunks = activeChunks,
                        onProgress = onProgress,
                    )
                }
            /**
             * Workers for chunks other than the first chunk need to wait initially
             * to avoid a race condition where the first request to the server
             * gets a `200` response with the full download, ignoring the requested range.
             * This would break the chunked downloader if a different chunk gets the `200`
             * (at the very least it would force a retry).
             * All following requests seem to correctly get partial `206` responses.
             */
            val firstDone = CompletableDeferred<Unit>()
            val workers =
                ranges.map { range ->
                    async(Dispatchers.IO) {
                        val isFirstChunk = range.start == 0L
                        if (!isFirstChunk) {
                            firstDone.await()
                        }
                        try {
                            downloadChunk(
                                url = url,
                                targetFile = targetFile,
                                range = range,
                                bytesDone = bytesDone,
                                firstDone = firstDone,
                                client = client,
                                maxRetries = maxRetries,
                                initialBackoffMillis = initialBackoffMillis,
                            )
                        } finally {
                            if (!firstDone.isCompleted) {
                                firstDone.complete(Unit)
                            }
                            activeChunks.decrementAndGet()
                        }
                    }
                }
            try {
                workers.awaitAll()
            } finally {
                progressJob.cancel()
            }
        }
    }

    private suspend fun emitProgressLoop(
        totalBytes: Long,
        bytesDone: AtomicLong,
        activeChunks: AtomicLong,
        onProgress: ((ParallelDownloadProgress) -> Unit)?,
    ) {
        if (onProgress == null) return
        var lastReported = -1f
        var lastEmitNanos = System.nanoTime()
        var lastEmitBytes = 0L
        var smoothedBytesPerSecond = 0.0
        try {
            while (currentCoroutineContext()[Job]?.isActive != false) {
                val nowBytes = bytesDone.get()
                val p = if (totalBytes > 0L) nowBytes.toFloat() / totalBytes else 0f
                if (p - lastReported >= 0.01f || nowBytes >= totalBytes) {
                    val nowNanos = System.nanoTime()
                    val instant =
                        instantBytesPerSecond(
                            bytesSinceLast = nowBytes - lastEmitBytes,
                            elapsedNanos = nowNanos - lastEmitNanos,
                        )
                    smoothedBytesPerSecond = 0.3 * instant + 0.7 * smoothedBytesPerSecond
                    lastEmitNanos = nowNanos
                    lastEmitBytes = nowBytes
                    lastReported = p
                    onProgress(
                        ParallelDownloadProgress(
                            progress = p.coerceIn(0f, 1f),
                            bytesPerSecond = smoothedBytesPerSecond.toLong(),
                            bytesDownloaded = nowBytes,
                            totalBytes = totalBytes,
                            activeChunks = activeChunks.get().toInt(),
                        )
                    )
                    if (totalBytes > 0L && nowBytes >= totalBytes) break
                }
                delay(PROGRESS_TICK_MILLIS)
            }
        } finally {
            // Terminal emit; runs on normal exit, on chunk-worker
            // completion (when the scope cancels us to release the
            // coroutineScope), and on cancellation. Guarantees the
            // UI sees a 100% report.
            onProgress(
                ParallelDownloadProgress(
                    progress = 1f,
                    bytesPerSecond = 0L,
                    bytesDownloaded = totalBytes,
                    totalBytes = totalBytes,
                    activeChunks = 0,
                )
            )
        }
    }

    /**
     * Downloads one byte range with retry. The shared `bytesDone` counter
     * is updated as bytes are written; on retry we re-seek the file to
     * the start of the range and rewrite the same bytes. The counter is
     * not decremented on retry (we're a strict superset of the bytes
     * we'd already written), so the aggregate progress monotonically
     * increases across the whole pool.
     */
    private fun downloadChunk(
        url: String,
        targetFile: File,
        range: ByteRange,
        bytesDone: AtomicLong,
        firstDone: CompletableDeferred<Unit>,
        client: OkHttpClient,
        maxRetries: Int,
        initialBackoffMillis: Long,
    ) {
        val totalAttempts = maxRetries + 1
        var lastError: Throwable? = null
        for (attempt in 0 until totalAttempts) {
            try {
                downloadChunkOnce(url, targetFile, range, bytesDone, firstDone, client)
                return
            } catch (e: Http4xxException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.tag(TAG).w(
                    e,
                    "Chunk %d-%d attempt %d/%d failed",
                    range.start, range.endInclusive, attempt + 1, totalAttempts,
                )
                lastError = e
                if (attempt < totalAttempts - 1) {
                    val backoff = initialBackoffMillis * (1L shl attempt)
                    try {
                        Thread.sleep(backoff)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Chunk download interrupted during backoff", ie)
                    }
                }
            }
        }
        throw IOException(
            "Chunk ${range.start}-${range.endInclusive} failed after $totalAttempts attempts",
            lastError,
        )
    }

    private fun downloadChunkOnce(
        url: String,
        targetFile: File,
        range: ByteRange,
        bytesDone: AtomicLong,
        firstDone: CompletableDeferred<Unit>,
        client: OkHttpClient,
    ) {
        val request =
            Request.Builder()
                .url(url)
                .header("Range", "bytes=${range.start}-${range.endInclusive}")
                .header("Accept-Encoding", "identity")
                .build()
        val response = client.newCall(request).execute()
        response.use {
            val isFirstChunk = range.start == 0L
            if (isFirstChunk) {
                firstDone.complete(Unit)
            }
            if (it.code == 416) {
                throw Http4xxException("Range not satisfiable for ${range.start}-${range.endInclusive}")
            }
            if (it.code in 400..499) {
                throw Http4xxException("Chunk ${range.start}-${range.endInclusive} HTTP ${it.code}")
            }
            if (it.code in 500..599) {
                throw IOException("Chunk HTTP ${it.code}")
            }
            if (it.code == 200 && !isFirstChunk) {
                // Because we won't read the stream until we're in the correct range, we throw here.
                throw IOException("Chunk byte range $range was ignored")
            }
            check(it.code == 200 || it.code == 206) {
                "Chunk expected 200/206, got ${it.code}"
            }
            val body = it.body
            val input = body.byteStream()
            val expectedSize = range.size
            val raf = RandomAccessFile(targetFile, "rw")
            raf.use { file ->
                file.seek(range.start)
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val toRead =
                        if (expectedSize - written < buffer.size) (expectedSize - written).toInt()
                        else buffer.size
                    if (toRead <= 0) break
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    file.write(buffer, 0, read)
                    written += read
                    bytesDone.addAndGet(read.toLong())
                }
                if (written != expectedSize) {
                    throw IOException(
                        "Chunk ${range.start}-${range.endInclusive} short: " +
                            "wrote $written of $expectedSize",
                    )
                }
            }
        }
    }

    private fun downloadOnce(
        url: String,
        targetFile: File,
        onProgress: ((DownloadProgress) -> Unit)?,
        client: OkHttpClient,
    ): File {
        // Accept-Encoding: identity skips the redundant gzip transport
        // compression the pack zip is already DEFLATE-compressed.
        val request =
          Request.Builder().url(url).header("Accept-Encoding", "identity").build()
        val response = client.newCall(request).execute()
        try {
            if (response.code in 400..499) {
                throw Http4xxException("Download failed: HTTP ${response.code} ${response.message}")
            }
            if (response.code in 500..599) {
                throw Http5xxException("Download failed: HTTP ${response.code} ${response.message}")
            }
            check(response.isSuccessful) { "Download failed: HTTP ${response.code} ${response.message}" }
            Timber.tag(TAG).d("HTTP %d for %s", response.code, url)
            val body = response.body
            val totalBytes = body.contentLength()
            if (totalBytes == 0L) throw EmptyBodyException("Empty response body")

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReported = -1f
                    var lastEmitNanos = System.nanoTime()
                    var lastEmitBytes = 0L
                    var smoothedBytesPerSecond = 0.0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        if (onProgress != null && totalBytes > 0) {
                            totalRead += bytesRead
                            val p = totalRead.toFloat() / totalBytes
                            if (p - lastReported >= 0.01f) {
                                val nowNanos = System.nanoTime()
                                val instantBytesPerSecond = instantBytesPerSecond(
                                    bytesSinceLast = totalRead - lastEmitBytes,
                                    elapsedNanos = nowNanos - lastEmitNanos,
                                )
                                smoothedBytesPerSecond =
                                    0.3 * instantBytesPerSecond + 0.7 * smoothedBytesPerSecond
                                lastEmitNanos = nowNanos
                                lastEmitBytes = totalRead
                                lastReported = p
                                onProgress(
                                    DownloadProgress(
                                        progress = p,
                                        bytesPerSecond = smoothedBytesPerSecond.toLong(),
                                        bytesDownloaded = totalRead,
                                        totalBytes = totalBytes,
                                    )
                                )
                            }
                        }
                    }
                    onProgress?.invoke(
                        DownloadProgress(
                            progress = 1f,
                            bytesPerSecond = 0L,
                            bytesDownloaded = totalRead,
                            totalBytes = totalBytes,
                        )
                    )
                }
            }
            if (targetFile.length() == 0L) throw EmptyBodyException("Downloaded file is empty")
            return targetFile
        } finally {
            response.close()
        }
    }

    private fun instantBytesPerSecond(bytesSinceLast: Long, elapsedNanos: Long): Double =
        if (elapsedNanos <= 0L) 0.0 else (bytesSinceLast * 1_000_000_000.0) / elapsedNanos

    /** Non-retriable: deterministic client error (HTTP 4xx). */
    private class Http4xxException(message: String) : IOException(message)

    /** Retriable: transient server error (HTTP 5xx). */
    private class Http5xxException(message: String) : IOException(message)

    /** Non-retriable: response body missing or empty. */
    private class EmptyBodyException(message: String) : IOException(message)
}

package com.skiletro.wheelwitch.util.io

import com.skiletro.wheelwitch.util.net.HttpClientProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Progress snapshot for an in-flight download, emitted to UI consumers. */
data class DownloadProgress(
    val progress: Float,
    val bytesPerSecond: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
)

/**
 * Aggregate progress across chunk workers, including workers waiting for the first response.
 */
data class ParallelDownloadProgress(
    val progress: Float,
    val bytesPerSecond: Long,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val activeChunks: Int,
)

/**
 * Blocking HTTP transfers with retries; the parallel entry point dispatches its work to IO.
 */
object FileDownloader {
  private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
  private const val TAG = "FileDownloader"

  /** Default number of byte-range workers. */
  const val DEFAULT_PARALLELISM: Int = 4

  /** Tick interval (ms) for the aggregate progress emitter in [downloadInParallel]. */
  private const val PROGRESS_TICK_MILLIS: Long = 200L

  /**
   * Downloads [url] to [targetFile]. Blocking - call from a background dispatcher.
   *
   * Retries [IOException] (including HTTP 5xx and file I/O errors) up to [maxRetries] times with
   * exponential backoff. HTTP 4xx and detected empty bodies are not retried; other non-2xx
   * responses fail immediately.
   *
   * @param onProgress Progress with a smoothed byte rate, throttled to ≥1% changes when length is
   *   known. Emits a zero snapshot before each attempt (after any backoff), and 100% after transfer.
   * @param client OkHttp client to use. Defaults to [HttpClientProvider.client]; pass
   *   [HttpClientProvider.largeDownloadClient] for big payloads to avoid spurious 15s read
   *   timeouts.
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
            .w(
                e,
                "Transient network failure on attempt %d/%d for %s",
                attempt + 1,
                totalAttempts,
                url,
            )
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
   * Downloads [url] in concurrent byte ranges on IO. Falls back to [downloadToFile] if HEAD fails
   * or does not advertise byte ranges and a positive content length.
   *
   * Sets the destination length before range writes; this does not guarantee disk space is
   * reserved or file contents are complete. Consumers must wait for success before reading.
   *
   * Each chunk retries [IOException] except HTTP 4xx. A worker failure cancels the coroutine
   * scope and the partial file is deleted after workers finish. Blocking requests and backoff
   * sleeps do not cooperatively observe coroutine cancellation.
   *
   * @param parallelism Byte-range worker count, from 1 to 16, reduced for smaller byte counts.
   * @param onProgress Aggregate smoothed rate and progress, polled every [PROGRESS_TICK_MILLIS]
   *   and emitted on ≥1% changes, plus 100% on success. The ranged path has no initial reset.
   */
  suspend fun downloadInParallel(
      url: String,
      targetFile: File,
      parallelism: Int = DEFAULT_PARALLELISM,
      onProgress: ((ParallelDownloadProgress) -> Unit)? = null,
      client: OkHttpClient = HttpClientProvider.largeDownloadClient,
      maxRetries: Int = 2,
      initialBackoffMillis: Long = 1000L,
  ): File =
      downloadInParallelInternal(
          url = url,
          targetFile = targetFile,
          parallelism = parallelism,
          onProgress = onProgress,
          client = client,
          maxRetries = maxRetries,
          initialBackoffMillis = initialBackoffMillis,
      )

  private suspend fun downloadInParallelInternal(
      url: String,
      targetFile: File,
      parallelism: Int,
      onProgress: ((ParallelDownloadProgress) -> Unit)?,
      client: OkHttpClient,
      maxRetries: Int,
      initialBackoffMillis: Long,
  ): File =
      withContext(Dispatchers.IO) {
        require(parallelism in 1..16) { "parallelism must be in 1..16" }
        require(maxRetries >= 0) { "maxRetries must be >= 0" }

        val (totalBytes, rangesSupported) = probeRanges(url, client)
        if (!rangesSupported || totalBytes <= 0L) {
          Timber.tag(TAG)
              .d(
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

        // Isolate asynchronous dispatcher limits; blocking execute() calls are not capped by them.
        val effectiveParallelism =
            if (totalBytes < parallelism.toLong()) totalBytes.toInt() else parallelism
        val parallelClient =
            client
                .newBuilder()
                .dispatcher(
                    Dispatcher().apply {
                      maxRequests = effectiveParallelism + 4
                      maxRequestsPerHost = effectiveParallelism
                    }
                )
                .build()

        val ranges = computeRanges(totalBytes, effectiveParallelism)
        val activeChunks = AtomicLong(ranges.size.toLong())
        val bytesDone = AtomicLong(0L)

        // Fix the final length before workers write disjoint ranges.
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
   * HEAD determines the final size before range writes. Failed probes or missing range/length
   * headers select the single-stream fallback.
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
      val endInclusive = if (i == parallelism - 1) totalBytes - 1 else cursor + chunkSize - 1
      ranges.add(ByteRange(cursor, endInclusive))
      cursor = endInclusive + 1
    }
    return ranges
  }

  /**
   * Runs N chunk workers in parallel, an aggregate progress emitter, and a per-chunk retry layer.
   * Suspends until all chunks succeed or any chunk exhausts its retries.
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
      val completedSuccessfully = AtomicBoolean(false)
      val progressJob =
          launch(Dispatchers.Default) {
            emitProgressLoop(
                totalBytes = totalBytes,
                bytesDone = bytesDone,
                activeChunks = activeChunks,
                onProgress = onProgress,
                completedSuccessfully = completedSuccessfully,
            )
          }
      /**
       * Gate later workers until the first response arrives: some servers ignore the initial
       * Range and return 200. Only the zero-offset worker can consume that response correctly.
       */
      val firstDone = CompletableDeferred<Unit>()
      val workers = ranges.map { range ->
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
        completedSuccessfully.set(true)
      } finally {
        progressJob.cancelAndJoin()
      }
    }
  }

  private suspend fun emitProgressLoop(
      totalBytes: Long,
      bytesDone: AtomicLong,
      activeChunks: AtomicLong,
      onProgress: ((ParallelDownloadProgress) -> Unit)?,
      completedSuccessfully: AtomicBoolean,
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
        if (nowBytes < totalBytes && p - lastReported >= 0.01f) {
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
        }
        delay(PROGRESS_TICK_MILLIS)
      }
    } finally {
      if (completedSuccessfully.get()) {
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
  }

  /** Downloads one byte range with retry and exact shared-byte accounting. */
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
        Timber.tag(TAG)
            .w(
                e,
                "Chunk %d-%d attempt %d/%d failed",
                range.start,
                range.endInclusive,
                attempt + 1,
                totalAttempts,
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
    var written = 0L
    try {
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
          // A full response starts at byte zero, not this worker's requested offset.
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
    } catch (e: IOException) {
      if (written > 0L) bytesDone.addAndGet(-written)
      throw e
    }
  }

  private fun downloadOnce(
      url: String,
      targetFile: File,
      onProgress: ((DownloadProgress) -> Unit)?,
      client: OkHttpClient,
  ): File {
    // Avoid redundant transport compression for zip payloads.
    val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
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
            totalRead += bytesRead
            if (onProgress != null && totalBytes > 0) {
              val p = totalRead.toFloat() / totalBytes
              if (p - lastReported >= 0.01f) {
                val nowNanos = System.nanoTime()
                val instantBytesPerSecond =
                    instantBytesPerSecond(
                        bytesSinceLast = totalRead - lastEmitBytes,
                        elapsedNanos = nowNanos - lastEmitNanos,
                    )
                smoothedBytesPerSecond = 0.3 * instantBytesPerSecond + 0.7 * smoothedBytesPerSecond
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
          if (totalBytes >= 0L && totalRead != totalBytes) {
            throw IOException("Downloaded $totalRead bytes, expected $totalBytes")
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

  /** HTTP 4xx bypasses retries. */
  private class Http4xxException(message: String) : IOException(message)

  /** HTTP 5xx uses the IOException retry path. */
  private class Http5xxException(message: String) : IOException(message)

  /** Detected empty bodies bypass retries. */
  private class EmptyBodyException(message: String) : IOException(message)
}

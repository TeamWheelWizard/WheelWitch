package com.skiletro.wheelwitch.util.log

/**
 * Thread-safe fixed-capacity ring buffer of [LogEntry]s.
 *
 * Drops the oldest entry at capacity. [MemoryBufferTree] supplies entries for bug-report export.
 */
class LogBuffer(private val capacity: Int = DEFAULT_CAPACITY) {
  init {
    require(capacity > 0) { "capacity must be > 0" }
  }

  private val entries = ArrayDeque<LogEntry>(capacity)
  private val lock = Any()

  fun write(entry: LogEntry) {
    synchronized(lock) {
      if (entries.size >= capacity) entries.removeFirst()
      entries.addLast(entry)
    }
  }

  /** Returns a stable snapshot of the buffer in insertion order (oldest first). */
  fun snapshot(): List<LogEntry> {
    synchronized(lock) { return entries.toList() }
  }

  fun clear() {
    synchronized(lock) { entries.clear() }
  }

  fun size(): Int = synchronized(lock) { entries.size }

  companion object {
    const val DEFAULT_CAPACITY = 1000

    /** Process-wide capture buffer. */
    val default: LogBuffer = LogBuffer()
  }
}

/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the playback event log for [PlaybackStatsRepository]: one in-memory copy
 * of the events plus an asynchronous writer.
 *
 * Why this exists: recording used to read, parse, prune and rewrite the entire
 * JSON file on the caller's thread while holding a global lock. One caller
 * (`MusicViewModel.finalizePlaybackTracking`) runs inside a `Player.Listener`
 * callback on the main thread, so every track transition blocked the UI on disk
 * I/O and on the lock — the "main thread waiting for a lock held by another
 * thread" ANR reported in issue #701.
 *
 * Now appends mutate the in-memory list (cheap, no disk access) and a single
 * background thread writes the file. `snapshot()` is served entirely from
 * memory after the first load, so statistics screens never block on I/O either.
 */
internal class PlaybackEventStore(
    private val file: File,
    private val maxHistoryAgeMs: Long = DEFAULT_MAX_HISTORY_AGE_MS,
    private val gson: Gson = Gson(),
    private val writer: Executor = defaultWriter()
) {
    private val lock = Any()
    private var cache: MutableList<PlaybackStatsRepository.PlaybackEvent>? = null

    /** Bumped on every mutation, so the writer knows whether it is behind. */
    private val version = AtomicLong(0)

    /** Last version durably written to [file]. */
    private val persistedVersion = AtomicLong(0)

    private val flushScheduled = AtomicBoolean(false)

    private val eventsType =
        object : TypeToken<MutableList<PlaybackStatsRepository.PlaybackEvent>>() {}.type

    /**
     * Appends [event] in memory, pruning anything older than the retention
     * window, and schedules a background write. Returns without touching disk.
     */
    fun append(event: PlaybackStatsRepository.PlaybackEvent) {
        synchronized(lock) {
            val events = loadedLocked()
            val cutoff = event.timestamp - maxHistoryAgeMs
            if (cutoff > 0) {
                events.removeAll { it.endMillis() < cutoff }
            }
            events.add(event)
        }
        version.incrementAndGet()
        scheduleFlush()
    }

    /** All known events, including ones not yet written to disk. */
    fun snapshot(): List<PlaybackStatsRepository.PlaybackEvent> =
        synchronized(lock) { loadedLocked().toList() }

    /** Drops every event, in memory and on disk. */
    fun clear() {
        synchronized(lock) { cache = mutableListOf() }
        version.incrementAndGet()
        scheduleFlush()
    }

    /** Blocks until every queued write has been attempted. Tests only. */
    fun awaitIdle(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (persistedVersion.get() == version.get() && !flushScheduled.get()) return
            Thread.sleep(5)
        }
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        writer.execute {
            val targetVersion = version.get()
            try {
                val events = synchronized(lock) { loadedLocked().toList() }
                writeLocked(events)
                persistedVersion.set(targetVersion)
            } finally {
                flushScheduled.set(false)
            }
            // A mutation landed while this write was running: go around again.
            if (persistedVersion.get() != version.get()) {
                scheduleFlush()
            }
        }
    }

    private fun loadedLocked(): MutableList<PlaybackStatsRepository.PlaybackEvent> {
        cache?.let { return it }
        val loaded = readFromDisk()
        cache = loaded
        return loaded
    }

    private fun readFromDisk(): MutableList<PlaybackStatsRepository.PlaybackEvent> {
        if (!file.exists()) return mutableListOf()
        val raw = runCatching { file.readText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return mutableListOf()

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            if (element != null && element.isJsonArray) {
                gson.fromJson<MutableList<PlaybackStatsRepository.PlaybackEvent>>(element, eventsType)
                    ?: mutableListOf()
            } else {
                mutableListOf()
            }
        }.getOrElse { mutableListOf() }
    }

    private fun writeLocked(events: List<PlaybackStatsRepository.PlaybackEvent>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(events))
        }
    }

    companion object {
        // Keep 90 days of history
        val DEFAULT_MAX_HISTORY_AGE_MS: Long = TimeUnit.DAYS.toMillis(90)

        private fun defaultWriter(): Executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "rhythm-playback-stats").apply { isDaemon = true }
            }
    }
}

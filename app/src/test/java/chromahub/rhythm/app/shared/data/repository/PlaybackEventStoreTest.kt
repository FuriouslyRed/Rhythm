/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.shared.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Regression tests for issue #701: recording a playback event must never block
 * the calling thread on disk I/O or on the writer lock.
 */
class PlaybackEventStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = System.currentTimeMillis()

    private fun event(
        songId: String = "song-1",
        timestamp: Long = now,
        durationMs: Long = TimeUnit.SECONDS.toMillis(180)
    ) = PlaybackStatsRepository.PlaybackEvent(
        songId = songId,
        timestamp = timestamp,
        durationMs = durationMs,
        startTimestamp = timestamp - durationMs,
        endTimestamp = timestamp,
        songTitle = "Title $songId",
        artistName = "Artist",
        albumName = "Album"
    )

    @Test
    fun `append does not write to disk on the calling thread`() {
        val file = File(tempFolder.root, "history.json")
        val writer = ManualExecutor()
        val store = PlaybackEventStore(file = file, writer = writer)

        store.append(event())

        assertFalse("append must not perform the write inline", file.exists())
        assertTrue("event is visible immediately", store.snapshot().size == 1)

        writer.runAll()

        assertTrue("write happens on the executor", file.exists())
        assertEquals(1, reload(file).size)
    }

    @Test
    fun `snapshot serves pending events without touching the file`() {
        val file = File(tempFolder.root, "history.json")
        val store = PlaybackEventStore(file = file, writer = ManualExecutor())

        repeat(3) { index -> store.append(event(songId = "song-$index")) }

        assertEquals(3, store.snapshot().map { it.songId }.toSet().size)
        assertFalse(file.exists())
    }

    @Test
    fun `events older than the retention window are pruned`() {
        val file = File(tempFolder.root, "history.json")
        val writer = ManualExecutor()
        val store = PlaybackEventStore(
            file = file,
            maxHistoryAgeMs = TimeUnit.DAYS.toMillis(90),
            writer = writer
        )

        store.append(event(songId = "ancient", timestamp = now - TimeUnit.DAYS.toMillis(120)))
        store.append(event(songId = "recent", timestamp = now))
        writer.runAll()

        val ids = reload(file).map { it.songId }
        assertEquals(listOf("recent"), ids)
    }

    @Test
    fun `concurrent appends are not lost`() {
        val file = File(tempFolder.root, "history.json")
        val store = PlaybackEventStore(file = file)
        val threads = 8
        val perThread = 100
        val start = CountDownLatch(1)

        val workers = (0 until threads).map { thread ->
            Thread {
                start.await()
                repeat(perThread) { index ->
                    store.append(event(songId = "t$thread-$index"))
                }
            }.apply { start() }
        }

        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        store.awaitIdle()

        val persisted = reload(file)
        assertEquals(threads * perThread, persisted.size)
        assertEquals(threads * perThread, persisted.map { it.songId }.toSet().size)
    }

    @Test
    fun `clear empties memory and disk`() {
        val file = File(tempFolder.root, "history.json")
        val store = PlaybackEventStore(file = file)

        store.append(event())
        store.awaitIdle()
        assertEquals(1, reload(file).size)

        store.clear()
        store.awaitIdle()

        assertTrue(store.snapshot().isEmpty())
        assertTrue(reload(file).isEmpty())
    }

    @Test
    fun `existing history file is loaded once and kept in memory`() {
        val file = File(tempFolder.root, "history.json")
        PlaybackEventStore(file = file).apply {
            append(event(songId = "persisted"))
            awaitIdle()
        }

        val reopened = PlaybackEventStore(file = file)
        assertEquals(listOf("persisted"), reopened.snapshot().map { it.songId })

        // Deleting the file must not affect a store that already loaded it.
        assertTrue(file.delete())
        assertEquals(1, reopened.snapshot().size)
    }

    private fun reload(file: File): List<PlaybackStatsRepository.PlaybackEvent> {
        val store = PlaybackEventStore(file = file)
        return store.snapshot()
    }

    /** Executor that only runs queued work when the test says so. */
    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks.addLast(command) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }
}

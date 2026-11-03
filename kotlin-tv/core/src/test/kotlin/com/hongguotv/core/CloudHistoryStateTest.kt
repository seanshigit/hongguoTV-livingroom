package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class CloudHistoryStateTest {
    private fun record(id: String, position: Long, time: Long) = BackupProgress(Series(id, "剧$id"), "${id}5", 0, position, 60000, false, time)

    @Test
    fun newerCloudProgressWinsBeforeUploadingStaleLocalProgress() {
        val state = CloudHistoryState("123")
        state.checkpoint(record("1", 1000, 100))
        state.checkpoint(record("2", 2000, 300))
        state.mergeRemote(listOf(record("1", 9000, 200), record("2", 1000, 200)))
        assertEquals(9000, state.history().first { it.series.id == "1" }.position)
        assertEquals(2000, state.history().first { it.series.id == "2" }.position)
        assertEquals(listOf("2"), state.pending().map { it.progress.series.id })
    }

    @Test
    fun acknowledgingAnOldWriteCannotEraseANewerCheckpoint() {
        val state = CloudHistoryState("123")
        state.checkpoint(record("1", 1000, 100))
        val old = state.pending().single()
        state.checkpoint(record("1", 2000, 200))
        state.acknowledge(old.sequence)
        assertEquals(2000, state.pending().single().progress.position)
        val restored = CloudHistoryState.decode("123", state.encode())
        assertEquals(state.history(), restored.history())
        assertEquals(state.pending(), restored.pending())
        assertThrows(IllegalArgumentException::class.java) { CloudHistoryState.decode("456", state.encode()) }
    }

    @Test
    fun locallyRemovedCloudHistoryStaysHiddenUntilPlayedAgain() {
        val state = CloudHistoryState("123")
        val item = record("1", 1000, 100)
        state.mergeRemote(listOf(item))
        state.remove("1")
        state.mergeRemote(listOf(item))
        assertTrue(state.history().isEmpty())
        state.checkpoint(item.copy(position = 2000, updatedAt = 200))
        assertEquals(1, state.history().size)
        assertEquals(1, state.pending().size)
    }

    @Test
    fun watchedMarksStayWithinTheAccountAndSurviveRestart() {
        val state = CloudHistoryState("123")
        state.checkpoint(record("1", 1000, 100))
        state.setWatched("1", true)
        assertEquals(setOf("1"), CloudHistoryState.decode("123", state.encode()).watched())
        state.remove("1")
        assertTrue(state.watched().isEmpty())
    }

    @Test
    fun guestImportDoesNotReplaceNewerAccountHistory() {
        val state = CloudHistoryState("123")
        state.mergeRemote(listOf(record("1", 5000, 300)))
        assertEquals(0, state.importLocal(listOf(record("1", 1000, 200))))
        assertEquals(5000, state.history().single().position)
        assertEquals(1, state.importLocal(listOf(record("1", 6000, 400))))
        assertEquals(6000, state.pending().single().progress.position)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PreparedSlotTest {
    private class Resource: Closeable {
        var closes=0
        override fun close() { closes++ }
    }

    @Test fun failedReadyPreparationIsClosedAndCannotBeClaimedAtEpisodeEnd() {
        val slot=PreparedSlot<Resource>({ 0L })
        val ticket=slot.begin("next:720")
        val failed=Resource()
        assertTrue(slot.complete(ticket,"next:720",failed))
        assertTrue(slot.invalidate(ticket,"next:720"))
        assertEquals(1,failed.closes)
        assertFalse(slot.isFresh("next:720"))
        assertNull(slot.take("next:720"))
    }

    @Test fun failurePostedAfterHandoffCannotCloseTheActiveResource() {
        val slot=PreparedSlot<Resource>({ 0L })
        val ticket=slot.begin("next:720")
        val active=Resource()
        slot.complete(ticket,"next:720",active)
        assertSame(active,slot.take("next:720"))
        assertFalse(slot.invalidate(ticket,"next:720"))
        assertEquals(0,active.closes)
        active.close()
    }

    @Test fun oldFailureCannotInvalidateANewerAttemptForTheSameEpisode() {
        val slot=PreparedSlot<Resource>({ 0L })
        val firstTicket=slot.begin("next:720")
        val first=Resource()
        slot.complete(firstTicket,"next:720",first)
        val secondTicket=slot.begin("next:720")
        val second=Resource()
        slot.complete(secondTicket,"next:720",second)
        assertEquals(1,first.closes)
        assertFalse(slot.invalidate(firstTicket,"next:720"))
        assertFalse(slot.invalidate(secondTicket,"other:720"))
        assertTrue(slot.isFresh("next:720"))
        assertSame(second,slot.take("next:720"))
        assertEquals(0,second.closes)
        second.close()
    }

    @Test fun queuedPublicationCannotUseFreshnessOfANewerPreparationForTheSameEpisode() {
        val slot=PreparedSlot<Resource>({ 0L })
        val oldTicket=slot.begin("next:720")
        val old=Resource()
        assertTrue(slot.complete(oldTicket,"next:720",old))
        // Completion has queued a UI publication, but a retry wins before it runs.
        val currentTicket=slot.begin("next:720")
        val current=Resource()
        assertTrue(slot.complete(currentTicket,"next:720",current))
        assertEquals(1,old.closes)
        assertTrue(slot.isFresh("next:720"))
        assertFalse(slot.isFresh(oldTicket,"next:720"))
        assertTrue(slot.isFresh(currentTicket,"next:720"))
        assertSame(current,slot.take("next:720"))
        assertFalse(slot.isFresh(currentTicket,"next:720"))
        current.close()
    }

    @Test fun completionAfterFailureDiscardsItsResourceWithoutReplacingNewerWork() {
        val slot=PreparedSlot<Resource>({ 0L })
        val oldTicket=slot.begin("next:720")
        val ready=CountDownLatch(1)
        val finish=CountDownLatch(1)
        val old=Resource()
        val worker=Thread {
            ready.countDown()
            finish.await(2,TimeUnit.SECONDS)
            slot.complete(oldTicket,"next:720",old)
        }
        worker.start()
        try {
            assertTrue(ready.await(2,TimeUnit.SECONDS))
            assertTrue(slot.invalidate(oldTicket,"next:720"))
            val current=Resource()
            val currentTicket=slot.begin("next:720")
            slot.complete(currentTicket,"next:720",current)
            finish.countDown(); worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(1,old.closes)
            assertSame(current,slot.take("next:720"))
            assertEquals(0,current.closes)
            current.close()
        } finally { finish.countDown(); worker.join(2000) }
    }
}

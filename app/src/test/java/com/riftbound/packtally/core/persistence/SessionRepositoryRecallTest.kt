package com.riftbound.packtally.core.persistence

import com.riftbound.packtally.core.persistence.SessionRepository.Companion.batchCountFor
import com.riftbound.packtally.core.persistence.SessionRepository.Companion.recallWaitCount
import com.riftbound.packtally.core.persistence.SessionRepository.Companion.recallWindowCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-function coverage for the "refresh all prices" batching and rate-limit
 * windowing math. One batch = up to 20 cards = one JustTCG request; the free
 * tier allows 10 requests/minute, so after every 10 batches the recall waits a
 * minute. These helpers drive both the recall loop and the confirmation copy,
 * so the boundaries (20-card batch, 10-call window) are pinned here.
 */
class SessionRepositoryRecallTest {

    @Test
    fun `batch count splits at twenty cards per request`() {
        assertEquals(0, batchCountFor(0))
        assertEquals(1, batchCountFor(1))
        assertEquals(1, batchCountFor(20))
        assertEquals(2, batchCountFor(21))
        assertEquals(10, batchCountFor(200))
        assertEquals(11, batchCountFor(201))
    }

    @Test
    fun `window count splits at ten calls per minute`() {
        assertEquals(0, recallWindowCount(0))
        assertEquals(1, recallWindowCount(1))
        assertEquals(1, recallWindowCount(10))
        assertEquals(2, recallWindowCount(11))
        assertEquals(2, recallWindowCount(20))
        assertEquals(3, recallWindowCount(21))
    }

    @Test
    fun `no wait until more than ten calls are required`() {
        // Up to 200 unique cards = 10 calls = no wait.
        assertEquals(0, recallWaitCount(batchCountFor(200)))
        // 201 unique cards = 11 calls = exactly one one-minute wait.
        assertEquals(1, recallWaitCount(batchCountFor(201)))
        // 400 unique cards = 20 calls = one wait; 401 = 21 calls = two waits.
        assertEquals(1, recallWaitCount(batchCountFor(400)))
        assertEquals(2, recallWaitCount(batchCountFor(401)))
    }

    @Test
    fun `wait count is window count minus one and never negative`() {
        assertEquals(0, recallWaitCount(0))
        assertEquals(0, recallWaitCount(10))
        assertEquals(1, recallWaitCount(11))
        assertEquals(1, recallWaitCount(20))
        assertEquals(2, recallWaitCount(21))
    }
}

package com.riftbound.packtally.core.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SessionRepositoryTest {

    @Test
    fun `submit labels show exact batch counts`() {
        assertEquals("No pending prices", SessionRepository.submitLabelFor(0))
        assertEquals("Submit 1 card", SessionRepository.submitLabelFor(1))
        assertEquals("Submit 18 cards", SessionRepository.submitLabelFor(18))
        assertEquals("Submit 20 cards", SessionRepository.submitLabelFor(20))
        assertEquals("Submit 21 cards in 2 batches", SessionRepository.submitLabelFor(21))
        assertEquals("Submit 46 cards in 3 batches", SessionRepository.submitLabelFor(46))
        assertEquals("Submit 100 cards in 5 batches", SessionRepository.submitLabelFor(100))
    }

    @Test
    fun `batch count uses max size twenty`() {
        assertEquals(0, SessionRepository.batchCountFor(0))
        assertEquals(1, SessionRepository.batchCountFor(18))
        assertEquals(1, SessionRepository.batchCountFor(20))
        assertEquals(2, SessionRepository.batchCountFor(21))
        assertEquals(3, SessionRepository.batchCountFor(46))
        assertEquals(5, SessionRepository.batchCountFor(100))
    }
}

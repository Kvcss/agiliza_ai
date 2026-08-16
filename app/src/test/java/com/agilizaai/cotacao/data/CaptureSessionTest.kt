package com.agilizaai.cotacao.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionTest {

    @Test
    fun `round trip keeps the resume point intact`() {
        val session = CaptureSession(
            conditions = listOf(7, 14, 21, 28),
            conditionIndex = 1,
            visitedKeys = linkedSetOf("aaa", "bbb", "ccc"),
            conditionCounts = mapOf(7 to 3879),
            skippedConjunto = 42,
            startedAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_900_000L
        )

        val decoded = CaptureSession.decode(session.encode())

        assertEquals(session, decoded)
        assertEquals(14, decoded!!.currentDays)
        assertEquals(3, decoded.readInCurrentCondition)
        assertEquals(listOf(14, 21, 28), decoded.remainingConditions)
    }

    @Test
    fun `a session of a single term is resumable while the term is unread`() {
        val session = CaptureSession(conditions = listOf(28))

        assertTrue(session.isResumable)
        assertEquals(28, session.currentDays)
        assertEquals(listOf(28), session.remainingConditions)
    }

    @Test
    fun `a finished session is no longer offered for resume`() {
        val session = CaptureSession(conditions = listOf(7, 14), conditionIndex = 2)

        assertFalse(session.isResumable)
        assertTrue(session.remainingConditions.isEmpty())
    }

    @Test
    fun `an unknown or corrupt payload never becomes a resume point`() {
        assertNull(CaptureSession.decode(""))
        assertNull(CaptureSession.decode("{\"conditions\":[7]}"))
        assertNull(CaptureSession.decode("agiliza-capture-session/1\nindex=1\n"))
    }

    @Test
    fun `the interrupted term is deduced from the base when there is no session file`() {
        // O caso real: 7 dias inteiro, 14 dias parado na metade, 21 e 28 sem começar.
        val recovered = CaptureRecovery.deduce(
            pricedByDays = mapOf(7 to 3879, 14 to 1109, 21 to 0, 28 to 0),
            conditions = listOf(7, 14, 21, 28)
        )!!

        assertEquals(14, recovered.currentDays)
        assertEquals(1109, recovered.readInCurrentCondition)
        assertEquals(listOf(14, 21, 28), recovered.remainingConditions)
    }

    @Test
    fun `a base with every term equally covered has nothing to resume`() {
        assertNull(
            CaptureRecovery.deduce(
                pricedByDays = mapOf(7 to 3879, 14 to 3879, 21 to 3879, 28 to 3879),
                conditions = listOf(7, 14, 21, 28)
            )
        )
    }

    @Test
    fun `an empty base offers no resume point`() {
        assertNull(CaptureRecovery.deduce(emptyMap(), listOf(7, 14, 21, 28)))
        assertNull(CaptureRecovery.deduce(mapOf(7 to 0, 14 to 0), listOf(7, 14)))
    }

    @Test
    fun `a capture of a single term resumes that term, not the sequence`() {
        val recovered = CaptureRecovery.deduce(
            pricedByDays = mapOf(28 to 500),
            conditions = listOf(28)
        )

        // O único prazo já é o mais completo que existe: não há prazo atrasado.
        assertNull(recovered)
    }

    @Test
    fun `visited keys survive even when a value line is unreadable`() {
        val payload = buildString {
            append("agiliza-capture-session/1\n")
            append("conditions=14\n")
            append("index=abc\n")
            append("counts=7:oops,14:5\n")
            append("keys\n")
            append("token-1\n")
            append("token-2\n")
        }

        val decoded = CaptureSession.decode(payload)!!

        assertEquals(listOf(14), decoded.conditions)
        assertEquals(0, decoded.conditionIndex)
        assertEquals(mapOf(14 to 5), decoded.conditionCounts)
        assertEquals(setOf("token-1", "token-2"), decoded.visitedKeys)
    }
}

package com.agilizaai.cotacao.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class QuoteRepositoryPolicyTest {
    @Test
    fun emptyOrUnreadableDraftFallsBackToProtectedBase() {
        assertNull(nonEmptyDraftOrNull<String>(null))
        assertNull(nonEmptyDraftOrNull(emptyList<String>()))

        val protected = listOf("produto anterior")
        val selected = nonEmptyDraftOrNull(emptyList<String>()) ?: protected

        assertEquals(protected, selected)
    }

    @Test
    fun nonEmptyDraftRemainsPreferred() {
        val draft = listOf("produto atual")

        assertSame(draft, nonEmptyDraftOrNull(draft))
    }
}

package com.agilizaai.cotacao.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteItemTest {
    @Test
    fun neverUsesUnitPriceForAPaymentTermThatWasNotCaptured() {
        val item = QuoteItem(
            id = "42",
            capturedAt = 1L,
            conditionDays = 7,
            sourcePackage = "br.com.ionsistemas.ionvendas",
            productCode = "42",
            barcode = "7891234567890",
            description = "Produto",
            quantity = "12",
            unitPrice = "10.500",
            rawCells = emptyList(),
            pricesByDays = mapOf(14 to "10.500"),
            unitsPerCase = "12"
        )

        assertEquals("", item.priceForDays(7))
        assertEquals("10.500", item.priceForDays(14))
        assertEquals("10.50", item.priceForWebsite(14))
        assertNull(item.casePriceForDays(7))
        assertEquals("126.000", item.casePriceForDays(14)?.toPlainString())
    }
}

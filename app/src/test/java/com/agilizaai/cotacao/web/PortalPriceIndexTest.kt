package com.agilizaai.cotacao.web

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPriceIndexTest {
    @Test
    fun excludesNormalizedBarcodeWhenCapturedPricesConflict() {
        val result = PortalPriceIndex.build(
            listOf(item("789.123.456.789-0", "10.000"), item("7891234567890", "12.000")),
            conditionDays = 7
        )

        assertTrue(result.entries.isEmpty())
        assertEquals(setOf("7891234567890"), result.conflictingBarcodes)
    }

    @Test
    fun deduplicatesEquivalentPricesForTheSameNormalizedBarcode() {
        val result = PortalPriceIndex.build(
            listOf(item("789.123.456.789-0", "10.0"), item("7891234567890", "10.000")),
            conditionDays = 7
        )

        assertEquals(listOf(PortalPriceIndex.Entry("7891234567890", "10.00")), result.entries)
        assertTrue(result.conflictingBarcodes.isEmpty())
    }

    @Test
    fun preservesDistinctBarcodes() {
        val result = PortalPriceIndex.build(
            listOf(item("7891234567890", "10.000"), item("7891234567891", "12.000")),
            conditionDays = 7
        )

        assertEquals(2, result.entries.size)
        assertTrue(result.conflictingBarcodes.isEmpty())
    }

    private fun item(barcode: String, price: String) = QuoteItem(
        id = "$barcode-$price",
        capturedAt = 1,
        conditionDays = 7,
        sourcePackage = "ion",
        productCode = "42",
        barcode = barcode,
        description = "PRODUTO",
        packaging = "CX-12",
        quantity = "12",
        unitPrice = price,
        rawCells = emptyList(),
        pricesByDays = mapOf(7 to price),
        unitsPerCase = "12"
    )
}

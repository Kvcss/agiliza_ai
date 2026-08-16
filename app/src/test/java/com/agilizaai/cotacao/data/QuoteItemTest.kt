package com.agilizaai.cotacao.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun acceptsOnlyConfirmedStockOfAtLeastThree() {
        val base = QuoteItem(
            id = "stock",
            capturedAt = 1L,
            conditionDays = 7,
            sourcePackage = "ion",
            productCode = "1",
            barcode = "7891234567890",
            description = "Produto",
            quantity = "1",
            unitPrice = "10",
            rawCells = emptyList()
        )

        assertFalse(base.copy(stock = "").hasMinimumStock())
        assertFalse(base.copy(stock = "indisponível").hasMinimumStock())
        assertFalse(base.copy(stock = "2,999").hasMinimumStock())
        assertTrue(base.copy(stock = "3,000").hasMinimumStock())
        assertTrue(base.copy(stock = "2.710,00").hasMinimumStock())
    }

    @Test
    fun authoritativeCasePriceIsNeverMultipliedAgain() {
        val item = QuoteItem(
            id = "nescau",
            capturedAt = 1L,
            conditionDays = 7,
            sourcePackage = "ion",
            productCode = "120.561",
            barcode = "17891000379585",
            description = "ACHOC NESCAU PO 200G",
            quantity = "6",
            unitPrice = "5.701",
            rawCells = emptyList(),
            pricesByDays = mapOf(7 to "5.701"),
            unitsPerCase = "6",
            casePricesByDays = mapOf(7 to "34.206")
        )

        assertEquals("5.701", item.priceForDays(7))
        assertEquals("34.206", item.casePriceForDays(7)?.toPlainString())
    }

    @Test
    fun migratesLegacyCasePriceOnlyWithPreciseCaseSize() {
        assertEquals(
            mapOf(7 to "5.791"),
            deriveUnitPricesFromCases(mapOf(7 to "34.746"), "6")
        )
        assertEquals(
            mapOf(7 to "5.791"),
            deriveUnitPricesFromCases(mapOf(7 to "138.984"), "24")
        )
        assertEquals(
            mapOf(7 to "7.203333"),
            deriveUnitPricesFromCases(mapOf(7 to "43.22"), "6")
        )
        assertTrue(deriveUnitPricesFromCases(mapOf(7 to "34.746"), "").isEmpty())
        assertEquals("2802", legacyStockFromRawCells(listOf("Estoque: 2802.0")))
        assertEquals("CJ", legacySalesUnitFromRawCells(listOf("Unidade: CJ")))
    }

    @Test
    fun conjuntoProductsNeverEnterTheBase() {
        val conjunto = base(salesUnit = "CJ")
        assertTrue(conjunto.hasMinimumStock())
        assertTrue(conjunto.isExcludedSalesUnit())
        assertFalse(conjunto.isEligibleForBase())

        // O ION às vezes só marca o conjunto na embalagem.
        assertTrue(base(salesUnit = "", packaging = "CJ-6").isExcludedSalesUnit())
        assertTrue(base(salesUnit = "cj").isExcludedSalesUnit())
    }

    @Test
    fun regularSalesUnitsStayInTheBase() {
        assertTrue(base(salesUnit = "UN").isEligibleForBase())
        assertTrue(base(salesUnit = "CX", packaging = "CX-12").isEligibleForBase())
        assertTrue(base(salesUnit = "PC").isEligibleForBase())
        // Estoque insuficiente continua sendo motivo de recusa.
        assertFalse(base(salesUnit = "UN", stock = "2").isEligibleForBase())
    }

    @Test
    fun trimsOversizedRawCellsBeforeStorage() {
        val noisy = base(salesUnit = "UN").copy(rawCells = List(200) { "campo $it" })
        assertEquals(QuoteItem.MAX_RAW_CELLS, noisy.withTrimmedRawCells().rawCells.size)
        assertEquals(3, base(salesUnit = "UN").copy(rawCells = listOf("a", "b", "c")).withTrimmedRawCells().rawCells.size)
    }

    private fun base(
        salesUnit: String,
        packaging: String = "1",
        stock: String = "10"
    ) = QuoteItem(
        id = "id-$salesUnit-$packaging",
        capturedAt = 1L,
        conditionDays = 7,
        sourcePackage = "ion",
        productCode = "120.561",
        barcode = "17891000379585",
        description = "ACHOC NESCAU PO 200G",
        packaging = packaging,
        quantity = "6",
        unitPrice = "5.701",
        rawCells = emptyList(),
        pricesByDays = mapOf(7 to "5.701"),
        unitsPerCase = "6",
        stock = stock,
        casePricesByDays = mapOf(7 to "34.206"),
        salesUnit = salesUnit
    )
}

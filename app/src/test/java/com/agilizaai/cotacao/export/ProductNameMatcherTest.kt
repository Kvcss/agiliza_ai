package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductNameMatcherTest {
    @Test
    fun matchesAccentsAndPunctuationButRejectsDifferentSizes() {
        val items = listOf(
            item("CHA CHIMARRÃO GAÚCHO 500G TRADICIONAL"),
            item("CHA CHIMARRÃO GAÚCHO 1KG TRADICIONAL")
        )
        assertEquals(items[0], ProductNameMatcher.find("Cha chimarrao gaucho 500g tradicional", items)?.item)
        assertNull(ProductNameMatcher.find("Cha chimarrao gaucho 250g tradicional", items))
    }

    @Test
    fun leavesAmbiguousDescriptionsUnmatched() {
        val items = listOf(item("COPO DESCARTAVEL BRANCO 180ML"), item("COPO DESCARTAVEL CRISTAL 180ML"))
        assertNull(ProductNameMatcher.find("COPO DESCARTAVEL 180ML", items))
    }

    @Test
    fun leavesExactNamePackagingVariantsUnmatched() {
        val items = listOf(
            item("SABONETE LIQUIDO", unitsPerCase = "4"),
            item("SABONETE LIQUIDO", unitsPerCase = "12")
        )

        assertNull(ProductNameMatcher.find("SABONETE LIQUIDO", items))
    }

    @Test
    fun usesQuotationCaseQuantityToSelectExactPackagingVariant() {
        val four = item("SABONETE LIQUIDO", unitsPerCase = "4")
        val twelve = item("SABONETE LIQUIDO", unitsPerCase = "12")

        assertEquals(twelve, ProductNameMatcher.find("SABONETE LIQUIDO", listOf(four, twelve), "12")?.item)
        assertEquals(four, ProductNameMatcher.find("SABONETE LIQUIDO", listOf(four, twelve), "4,0")?.item)
    }

    @Test
    fun doesNotFallbackWhenRequestedCaseDoesNotExist() {
        val items = listOf(item("SABONETE LIQUIDO", unitsPerCase = "4"))

        assertNull(ProductNameMatcher.find("SABONETE LIQUIDO", items, "12"))
    }

    private fun item(name: String, unitsPerCase: String = "12") = QuoteItem(
        id = "$name-$unitsPerCase",
        capturedAt = 1,
        conditionDays = 7,
        sourcePackage = "ion",
        productCode = name,
        barcode = "",
        description = name,
        packaging = "CX-$unitsPerCase",
        quantity = unitsPerCase,
        unitPrice = "10",
        rawCells = emptyList(),
        pricesByDays = mapOf(7 to "10", 14 to "11", 21 to "12", 28 to "13"),
        unitsPerCase = unitsPerCase
    )
}

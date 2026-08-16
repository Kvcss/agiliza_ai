package com.agilizaai.cotacao.web

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalMatcherTest {

    @Test
    fun matchesByFullBarcodeFirst() {
        val index = PortalMatcher.buildIndex(
            listOf(item("051435", "7501843503503", "ACENDEDOR MULTIUSO BIC EZ RECH UN", "3.19")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "051435", "7501843503503", "ACENDEDOR MULTIUSO BIC EZ RECH UN"))
        )

        assertEquals(1, report.matched)
        assertEquals("3.19", report.resolutions.single().price)
        assertEquals(PortalMatcher.Signal.BARCODE, report.resolutions.single().signal)
    }

    @Test
    fun completesATruncatedIonBarcodeWhenTheNameConfirmsIt() {
        // A lista do ION cortou o EAN em "006192058019"; o portal traz o código inteiro.
        val index = PortalMatcher.buildIndex(
            listOf(item("116068", "006192058019", "*EXPOSITOR HAVAIANAS 10 GANCHOS(120 PARES)", "364.17")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "999999", "0061920580192", "EXPOSITOR HAVAIANAS 10 GANCHOS"))
        )

        assertEquals(1, report.matched)
        assertEquals("364.17", report.resolutions.single().price)
        assertEquals(PortalMatcher.Signal.BARCODE_PREFIX, report.resolutions.single().signal)
    }

    @Test
    fun refusesATruncatedBarcodeWhenTheNameIsADifferentProduct() {
        val index = PortalMatcher.buildIndex(
            listOf(item("116068", "006192058019", "*EXPOSITOR HAVAIANAS 10 GANCHOS(120 PARES)", "364.17")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "", "0061920580192", "COPO DESC CRISTALCOPO 200ML"))
        )

        assertEquals(0, report.matched)
        assertEquals(listOf(0), report.unmatched)
    }

    @Test
    fun leavesTheRowBlankWhenTwoTruncatedCodesSharePrefixAndPrices() {
        val index = PortalMatcher.buildIndex(
            listOf(
                item("116068", "006192058019", "*EXPOSITOR HAVAIANAS 10 GANCHOS", "364.17"),
                item("44313", "006192058019", "*EXPOSITOR HAVAIANAS 20 GANCHOS", "663.38")
            ),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "", "0061920580192", "EXPOSITOR HAVAIANAS"))
        )

        assertEquals(0, report.matched)
        assertEquals(listOf(0), report.ambiguous)
    }

    @Test
    fun fallsBackToProductCodeIgnoringIonThousandSeparators() {
        val index = PortalMatcher.buildIndex(
            listOf(item("33.427", "", "COPO DESC COPOMAIS 200ML", "4.99")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "033427", "", "COPO DESC COPOMAIS 200ML"))
        )

        assertEquals(1, report.matched)
        assertEquals(PortalMatcher.Signal.CODE, report.resolutions.single().signal)
        assertEquals("4.99", report.resolutions.single().price)
    }

    @Test
    fun refusesAMatchingCodeWhenTheNameIsClearlyAnotherProduct() {
        val index = PortalMatcher.buildIndex(
            listOf(item("33.427", "", "COPO DESC COPOMAIS 200ML", "4.99")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "033427", "", "CHUVEIRO DUCHA ZAGONEL GORDUCHA 5450W"))
        )

        assertEquals(0, report.matched)
        assertEquals(listOf(0), report.ambiguous)
    }

    @Test
    fun fallsBackToTheProductNameWhenThereIsNoCode() {
        val index = PortalMatcher.buildIndex(
            listOf(item("", "", "FILME PVC WYDA 30M", "12.40")),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "054595", "", "FILME PVC WYDA 30M"))
        )

        assertEquals(1, report.matched)
        assertEquals(PortalMatcher.Signal.NAME, report.resolutions.single().signal)
    }

    @Test
    fun blocksABarcodeThatHoldsTwoDifferentPrices() {
        val index = PortalMatcher.buildIndex(
            listOf(
                item("1", "7897147500264", "COPO DESC COPOMAIS 200ML", "4.99"),
                item("2", "7897147500264", "COPO DESC COPOMAIS 200ML", "5.49")
            ),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(PortalMatcher.Row(0, "033427", "7897147500264", "COPO DESC COPOMAIS 200ML"))
        )

        assertEquals(0, report.matched)
        assertEquals(listOf(0), report.conflicting)
        assertTrue(index.conflictingKeys.contains("ean:7897147500264"))
    }

    @Test
    fun ignoresItemsWithoutAPriceForTheSelectedTerm() {
        val index = PortalMatcher.buildIndex(
            listOf(item("051435", "7501843503503", "ACENDEDOR MULTIUSO BIC EZ RECH UN", "3.19", days = 7)),
            28
        )
        assertTrue(index.isEmpty)
    }

    @Test
    fun reportsWhichSignalResolvedEachRow() {
        val index = PortalMatcher.buildIndex(
            listOf(
                item("1", "7501843503503", "ACENDEDOR MULTIUSO BIC EZ RECH UN", "3.19"),
                item("033427", "", "COPO DESC COPOMAIS 200ML", "4.99")
            ),
            7
        )
        val report = PortalMatcher.resolve(
            index,
            listOf(
                PortalMatcher.Row(0, "051435", "7501843503503", "ACENDEDOR MULTIUSO BIC EZ RECH UN"),
                PortalMatcher.Row(1, "033427", "", "COPO DESC COPOMAIS 200ML"),
                PortalMatcher.Row(2, "099999", "7899999999999", "PRODUTO QUE NAO EXISTE NA BASE")
            )
        )

        assertEquals(2, report.matched)
        assertEquals(1, report.signalCount(PortalMatcher.Signal.BARCODE))
        assertEquals(1, report.signalCount(PortalMatcher.Signal.CODE))
        assertEquals(listOf(2), report.unmatched)
    }

    private fun item(
        code: String,
        barcode: String,
        description: String,
        price: String,
        days: Int = 7
    ) = QuoteItem(
        id = "$code-$barcode-$description",
        capturedAt = 1L,
        conditionDays = days,
        sourcePackage = "br.com.ionsistemas.ionvendas",
        productCode = code,
        barcode = barcode,
        description = description,
        packaging = "1",
        quantity = "1",
        unitPrice = price,
        rawCells = emptyList(),
        pricesByDays = mapOf(days to price),
        unitsPerCase = "1",
        stock = "10",
        casePricesByDays = mapOf(days to price),
        salesUnit = "UN"
    )
}

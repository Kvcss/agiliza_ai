package com.agilizaai.cotacao.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowParserTest {
    @Test
    fun parsesRealBlinkMobileVerticalCardsAndIgnoresCartonTotal() {
        val nodes = listOf(
            node("Blink mobile", 10, 20, 180),
            node("todos", 190, 90, 300),
            node("ABACAXI CALDA TOZZI 400G", 16, 150, 390),
            node("Cód.: 3468-12", 156, 190, 330),
            node("1709500", 480, 190, 560),
            node("Emb.: CX-12", 156, 225, 330),
            node("R$ 210,77", 480, 225, 560),
            node("UN: R$ 17,564", 156, 260, 340),
            node("Estoque: 6", 156, 295, 300),
            node("EAN: 7898909755380", 16, 335, 260),
            node("Prazo de Validade 944 dias", 16, 390, 350),
            node("ABACAXI RODELAS SCHRAMM 400G", 16, 450, 440),
            node("Cód.: 3472-24", 156, 490, 330),
            node("1709500", 480, 490, 560),
            node("Emb.: CX-24", 156, 525, 330),
            node("R$ 458,14", 480, 525, 560),
            node("UN: R$ 19,089", 156, 560, 340),
            node("Estoque: 19", 156, 595, 310),
            node("EAN: 7896536500168", 16, 635, 260)
        )

        val items = RowParser(rowTolerancePx = 12)
            .parse(nodes, 7, "br.com.blink.mobile", capturedAt = 1_000L)

        assertEquals(2, items.size)
        assertEquals("3468-12", items[0].productCode)
        assertEquals("7898909755380", items[0].barcode)
        assertEquals("ABACAXI CALDA TOZZI 400G", items[0].description)
        assertEquals("12", items[0].quantity)
        assertEquals("17.564", items[0].unitPrice)
        assertEquals("19.089", items[1].unitPrice)
        assertTrue(items[0].rawCells.contains("Emb.: CX-12"))
    }

    @Test
    fun parsesBlinkFieldsSplitAcrossAccessibilityNodes() {
        val nodes = listOf(
            node("ABS ALWAYS BASICO C/8 PQ SECO C/ABAS", 16, 120, 430),
            node("Cód.:", 156, 160, 215),
            node("3511-18", 220, 160, 300),
            node("Emb.:", 156, 195, 215),
            node("CX-18", 220, 195, 285),
            node("UN:", 156, 230, 200),
            node("R$ 3,010", 205, 230, 300),
            node("EAN:", 16, 270, 70),
            node("7500435127226", 76, 270, 240)
        )

        val item = RowParser(rowTolerancePx = 12)
            .parse(nodes, 7, "br.com.blink.mobile")
            .single()

        assertEquals("3511-18", item.productCode)
        assertEquals("7500435127226", item.barcode)
        assertEquals("ABS ALWAYS BASICO C/8 PQ SECO C/ABAS", item.description)
        assertEquals("18", item.quantity)
        assertEquals("3.010", item.unitPrice)
    }

    @Test
    fun waitsForPartiallyVisibleBlinkCardInsteadOfCreatingBarcodeOnlyItem() {
        val nodes = listOf(
            node("EAN: 7898909755380", 16, 20, 260),
            node("ABACAXI RODELAS SCHRAMM 400G", 16, 80, 440),
            node("Cód.: 3472-24", 156, 120, 330),
            node("Emb.: CX-24", 156, 155, 330),
            node("UN: R$ 19,089", 156, 190, 340)
        )

        val items = RowParser(rowTolerancePx = 12)
            .parse(nodes, 7, "br.com.blink.mobile")

        assertTrue(items.isEmpty())
    }

    @Test
    fun doesNotAttachFieldsFromPreviousCardAtTopOfScrolledPage() {
        val nodes = listOf(
            node("Emb.: CX-12", 156, 5, 330),
            node("UN: R$ 17,564", 156, 40, 340),
            node("EAN: 7898909755380", 16, 75, 260),
            node("ABACAXI RODELAS SCHRAMM 400G", 16, 130, 440),
            node("Cód.: 3472-24", 156, 170, 330),
            node("Emb.: CX-24", 156, 205, 330),
            node("UN: R$ 19,089", 156, 240, 340),
            node("EAN: 7896536500168", 16, 280, 260)
        )

        val item = RowParser(rowTolerancePx = 12)
            .parse(nodes, 7, "br.com.blink.mobile")
            .single()

        assertEquals("7896536500168", item.barcode)
        assertEquals("24", item.quantity)
        assertEquals("19.089", item.unitPrice)
    }

    @Test
    fun selectsPriceColumnMatchingConditionDays() {
        val nodes = listOf(
            node("Produto", 0, 100, 200),
            node("7 dias", 300, 100, 380),
            node("14 dias", 400, 100, 490),
            node("21 dias", 510, 100, 600),
            node("ABC-42", 0, 200, 80),
            node("7891234567890", 90, 200, 230),
            node("Café torrado premium", 240, 200, 390),
            node("10,50", 300, 200, 380),
            node("9,90", 410, 200, 485),
            node("9,50", 520, 200, 595)
        )

        val items = RowParser(rowTolerancePx = 12).parse(nodes, 14, "br.com.ionsistemas.ionvendas", 1000L)

        assertEquals(1, items.size)
        assertEquals("7891234567890", items.single().barcode)
        assertEquals("Café torrado premium", items.single().description)
        assertEquals("9.90", items.single().unitPrice)
        assertEquals(14, items.single().conditionDays)
    }

    @Test
    fun reusesPreviouslyValidatedPriceColumnAfterHeaderScrollsAway() {
        val firstPage = listOf(
            node("7 dias", 300, 100, 380),
            node("14 dias", 400, 100, 490),
            node("21 dias", 510, 100, 600)
        )
        val secondPage = listOf(
            node("ABC-42", 0, 200, 80),
            node("7891234567890", 90, 200, 230),
            node("Café torrado premium", 240, 200, 390),
            node("10,50", 300, 200, 380),
            node("10,80", 410, 200, 485),
            node("11,10", 520, 200, 595)
        )
        val parser = RowParser(rowTolerancePx = 12)
        val selectedColumn = parser.findConditionColumnX(firstPage, 14)

        val item = parser.parse(
            nodes = secondPage,
            conditionDays = 14,
            sourcePackage = "br.com.ionsistemas.ionvendas",
            conditionColumnX = selectedColumn
        ).single()

        assertEquals("10.80", item.unitPrice)
        assertEquals("", item.quantity)
    }

    @Test
    fun refusesAmbiguousMultiPriceRowWithoutAValidatedHeader() {
        val nodes = listOf(
            node("ABC-42", 0, 200, 80),
            node("7891234567890", 90, 200, 230),
            node("Café torrado premium", 240, 200, 390),
            node("10,50", 300, 200, 380),
            node("10,80", 410, 200, 485)
        )

        val items = RowParser(rowTolerancePx = 12).parse(nodes, 14, "br.com.ionsistemas.ionvendas")

        assertTrue(items.isEmpty())
    }

    @Test
    fun preservesBrazilianCurrencyWithoutUsingDouble() {
        val nodes = listOf(
            node("000123", 0, 150, 80),
            node("7891234567890", 90, 150, 230),
            node("Produto de teste", 240, 150, 390),
            node("R$ 1.234,56", 420, 150, 560)
        )

        val item = RowParser(rowTolerancePx = 12)
            .parse(nodes, 7, "br.com.ionsistemas.ionvendas")
            .single()

        assertEquals("1234.56", item.unitPrice)
        assertTrue(item.id.isNotBlank())
    }

    private fun node(text: String, left: Int, top: Int, right: Int) = NodeSnapshot(
        text = text,
        viewId = "",
        className = "android.widget.TextView",
        packageName = "br.com.ionsistemas.ionvendas",
        left = left,
        top = top,
        right = right,
        bottom = top + 30,
        childCount = 0
    )
}

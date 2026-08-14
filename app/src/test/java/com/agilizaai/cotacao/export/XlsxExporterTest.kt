package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class XlsxExporterTest {
    @Test
    fun createsValidOfficeOpenXmlPackageWithThreeSheets() {
        val output = ByteArrayOutputStream()
        XlsxExporter.write(
            output,
            listOf(
                QuoteItem(
                    id = "1",
                    capturedAt = 1_700_000_000_000L,
                    conditionDays = 21,
                    sourcePackage = "br.com.ionsistemas.ionvendas",
                    productCode = "00042",
                    barcode = "07891234567890",
                    description = "Produto & teste",
                    packaging = "CX-12",
                    quantity = "2",
                    unitPrice = "1234.56",
                    rawCells = listOf("00042", "Produto & teste", "R$ 1.234,56"),
                    pricesByDays = mapOf(
                        7 to "1234.56",
                        14 to "1243.20",
                        21 to "1251.84",
                        28 to "1260.49"
                    )
                )
            ),
            exportedAt = 1_700_000_000_000L
        )
        File("build/qa").mkdirs()
        File("build/qa/cotacao-exemplo.xlsx").writeBytes(output.toByteArray())

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(10, entries.size)
        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet3.xml"))
        assertTrue(entries.getValue("xl/workbook.xml").contains("Cotação"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("07891234567890"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("1234.56"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("1243.20"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("1251.84"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("1260.49"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("Produto &amp; teste"))
    }

    @Test
    fun neverDerivesMissingIonTermsFromAnyEstimatedRate() {
        val output = ByteArrayOutputStream()
        XlsxExporter.write(
            output = output,
            items = listOf(
                QuoteItem(
                    id = "only-seven",
                    capturedAt = 1L,
                    conditionDays = 7,
                    sourcePackage = "br.com.ionsistemas.ionvendas",
                    productCode = "42",
                    barcode = "7891234567890",
                    description = "Produto com prazo incompleto",
                    packaging = "1",
                    quantity = "1",
                    unitPrice = "10.000",
                    rawCells = emptyList(),
                    pricesByDays = mapOf(7 to "10.000")
                )
            ),
            supplierName = "Vila Nova"
        )

        var table = ""
        var pending = ""
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    "xl/worksheets/sheet1.xml" -> table = zip.readBytes().toString(Charsets.UTF_8)
                    "xl/worksheets/sheet2.xml" -> pending = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }

        assertTrue(table.contains("<v>10.000</v>"))
        assertFalse(table.contains("<v>10.07</v>"))
        assertFalse(table.contains("<v>10.14</v>"))
        assertFalse(table.contains("<v>10.21</v>"))
        assertTrue(pending.contains("14/21/28 dias"))
    }

    @Test
    fun exportsOnePaymentTermAndKeepsPackageVariantsAsSeparateRows() {
        val output = ByteArrayOutputStream()
        val common = QuoteItem(
            id = "variant-4",
            capturedAt = 1_700_000_000_000L,
            conditionDays = 14,
            sourcePackage = "br.com.ionsistemas.ionvendas",
            productCode = "11-111-2",
            barcode = "7891111000001",
            description = "Produto caixa 4",
            packaging = "CX",
            quantity = "1",
            unitPrice = "20.000",
            rawCells = emptyList(),
            pricesByDays = mapOf(7 to "19.000", 14 to "20.000", 21 to "21.000"),
            unitsPerCase = "4"
        )
        XlsxExporter.write(
            output = output,
            items = listOf(
                common,
                common.copy(
                    id = "variant-12",
                    description = "Produto caixa 12",
                    packaging = "FD",
                    unitsPerCase = "12",
                    pricesByDays = mapOf(7 to "54.000", 14 to "56.000", 21 to "58.000")
                ),
                common.copy(
                    id = "only-seven",
                    description = "Produto sem preço de 14 dias",
                    pricesByDays = mapOf(7 to "30.000")
                )
            ),
            conditionDays = 14
        )

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val table = entries.getValue("xl/worksheets/sheet1.xml")
        val workbook = entries.getValue("xl/workbook.xml")
        val pending = entries.getValue("xl/worksheets/sheet2.xml")

        assertTrue(workbook.contains("name=\"14 dias\""))
        assertTrue(table.contains("14 dias"))
        assertFalse(table.contains("7 dias"))
        assertFalse(table.contains("21 dias"))
        assertFalse(table.contains("28 dias"))
        assertTrue(table.contains("Produto caixa 4"))
        assertTrue(table.contains("Produto caixa 12"))
        assertFalse(table.contains("Produto sem preço de 14 dias"))
        assertTrue(table.contains("<v>4</v>"))
        assertTrue(table.contains("<v>12</v>"))
        assertEquals(2, "11-111-2".toRegex(RegexOption.LITERAL).findAll(table).count())
        assertFalse(pending.contains("7/21/28 dias"))
    }
}

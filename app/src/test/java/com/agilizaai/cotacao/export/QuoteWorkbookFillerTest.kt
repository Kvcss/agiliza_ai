package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class QuoteWorkbookFillerTest {
    @Test
    fun preservesWorkbookAndFillsOnlyMatchedCasePrice() {
        val input = workbook()
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(input),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10.25", "20")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val entries = unzip(output.toByteArray())
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")

        assertEquals("Cotação", result.targetSheet)
        assertEquals("O", result.targetColumn)
        assertEquals(1, result.matched)
        assertEquals(1, result.unmatched)
        assertTrue(sheet.contains("r=\"O3\""))
        assertTrue(sheet.contains("<v>205</v>"))
        assertTrue(sheet.contains("r=\"O4\"") && !sheet.substringAfter("r=\"O4\"").substringBefore("</c>").contains("<v>"))
        assertTrue(sheet.contains("<f>O3*2</f>"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("<v>123</v>"))
        assertEquals("keep-me", entries.getValue("custom/data.txt"))
    }

    @Test
    fun leavesCellBlankWhenExactNameHasDifferentCaseVariants() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook()),
            output = output,
            items = listOf(
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "4"),
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(0, result.matched)
        assertEquals(2, result.unmatched)
        assertTrue(sheet.contains("<c r=\"O3\" s=\"4\"/>"))
    }

    @Test
    fun usesCxCColumnToSelectVariantAndCalculateItsCasePrice() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(caseUnits = "12")),
            output = output,
            items = listOf(
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "5", "4"),
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(1, result.matched)
        assertTrue(
            sheet.contains("r=\"O3\"") &&
                sheet.substringAfter("r=\"O3\"").substringBefore("</c>").contains("<v>120</v>")
        )
    }

    @Test
    fun usesRepeatedCxCLabelAndAdjacentQuantityFromProductRow() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(repeatedCaseUnits = "12")),
            output = output,
            items = listOf(
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "5", "4"),
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(1, result.matched)
        assertTrue(sheet.contains("<c r=\"D3\" t=\"s\"><v>3</v></c>"))
        assertTrue(sheet.contains("<c r=\"E3\"><v>12</v></c>"))
        assertTrue(
            sheet.contains("r=\"O3\"") &&
                sheet.substringAfter("r=\"O3\"").substringBefore("</c>").contains("<v>120</v>")
        )
    }

    @Test
    fun leavesPriceBlankWhenRepeatedCaseLabelsConflict() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(repeatedCaseUnits = "12", conflictingCaseUnits = "4")),
            output = output,
            items = listOf(
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "5", "4"),
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(0, result.matched)
        assertTrue(sheet.contains("<c r=\"O3\" s=\"4\"/>"))
    }

    @Test
    fun acceptsUnabbreviatedVilaNovaHeaderToo() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(supplierHeader = "VILA NOVA", caseUnits = "12")),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )

        assertEquals("O", result.targetColumn)
        assertEquals(1, result.matched)
    }

    @Test
    fun doesNotTreatNamesThatOnlyStartWithVilaAsVilaNova() {
        val failure = runCatching {
            QuoteWorkbookFiller.fill(
                input = ByteArrayInputStream(workbook(supplierHeader = "VILA RICA")),
                output = ByteArrayOutputStream(),
                items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
                conditionDays = 21,
                supplierName = "Vila Nova"
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Vila Nova"))
    }

    private fun item(name: String, price: String, units: String) = QuoteItem(
        id = "$name-$units", capturedAt = 1, conditionDays = 7, sourcePackage = "ion",
        productCode = "42", barcode = "789", description = name, packaging = "CX-$units",
        quantity = units, unitPrice = "9", rawCells = emptyList(),
        pricesByDays = mapOf(7 to "9", 14 to "10", 21 to price, 28 to "11"), unitsPerCase = units
    )

    private fun workbook(
        supplierHeader: String = "VILA",
        caseUnits: String? = null,
        repeatedCaseUnits: String? = null,
        conflictingCaseUnits: String? = null
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.add("xl/workbook.xml", """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Cotação" sheetId="1" r:id="rId1"/><sheet name="Referência" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            zip.add("xl/_rels/workbook.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""")
            zip.add("xl/sharedStrings.xml", """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>$supplierHeader</t></si><si><t>CHÁ CHIMARRÃO GAÚCHO 500G</t></si><si><t>PRODUTO NÃO ENCONTRADO 2KG</t></si><si><t>CX C/</t></si></sst>""")
            val caseHeader = if (caseUnits != null) "<c r=\"D2\" t=\"s\"><v>3</v></c>" else ""
            val caseCell = when {
                caseUnits != null -> "<c r=\"D3\"><v>$caseUnits</v></c>"
                repeatedCaseUnits != null -> buildString {
                    append("<c r=\"D3\" t=\"s\"><v>3</v></c><c r=\"E3\"><v>$repeatedCaseUnits</v></c>")
                    if (conflictingCaseUnits != null) {
                        append("<c r=\"F3\" t=\"s\"><v>3</v></c><c r=\"G3\"><v>$conflictingCaseUnits</v></c>")
                    }
                }
                else -> ""
            }
            zip.add("xl/worksheets/sheet1.xml", """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="2">$caseHeader<c r="O2" t="s"><v>0</v></c></row><row r="3"><c r="B3" t="s"><v>1</v></c>$caseCell<c r="O3" s="4"/><c r="Q3"><f>O3*2</f><v>0</v></c></row><row r="4"><c r="B4" t="s"><v>2</v></c><c r="O4" s="4"/></row></sheetData></worksheet>""")
            zip.add("xl/worksheets/sheet2.xml", """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>123</v></c></row></sheetData></worksheet>""")
            zip.add("custom/data.txt", "keep-me")
        }
        output.toByteArray()
    }

    private fun ZipOutputStream.add(name: String, content: String) {
        putNextEntry(ZipEntry(name)); write(content.toByteArray()); closeEntry()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }
}

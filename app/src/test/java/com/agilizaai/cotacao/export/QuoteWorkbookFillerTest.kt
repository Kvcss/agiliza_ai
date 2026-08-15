package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class QuoteWorkbookFillerTest {
    @Test
    fun renamesNomeHeaderAndFillsOnlyThatColumnWithCapturedCasePrice() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook()),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val entries = unzip(output.toByteArray())
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        val styles = entries.getValue("xl/styles.xml")

        assertEquals("Cotação", result.targetSheet)
        assertEquals("H", result.targetColumn)
        assertEquals(1, result.matched)
        assertEquals(1, result.unmatched)
        assertTrue(cell(sheet, "H2").contains("s=\"30\""))
        assertTrue(cell(sheet, "H2").contains("t=\"inlineStr\""))
        assertTrue(cell(sheet, "H2").contains("<t>Vila</t>"))
        val priceStyleIndex = styleIndex(cell(sheet, "H3"))
        val priceStyle = cellXf(styles, priceStyleIndex)
        assertEquals(5, priceStyleIndex)
        assertEquals(6, cellXfCount(styles))
        assertEquals("166", priceStyle.getAttribute("numFmtId"))
        assertEquals("1", priceStyle.getAttribute("applyNumberFormat"))
        assertEquals("7", priceStyle.getAttribute("fontId"))
        assertEquals("3", priceStyle.getAttribute("fillId"))
        assertEquals("2", priceStyle.getAttribute("borderId"))
        assertEquals("9", priceStyle.getAttribute("xfId"))
        assertEquals("1", priceStyle.getAttribute("applyAlignment"))
        assertEquals(
            "right",
            (priceStyle.getElementsByTagNameNS("*", "alignment").item(0) as Element).getAttribute("horizontal")
        )
        assertTrue(cell(sheet, "H3").contains("<v>34.206</v>"))
        assertFalse(cell(sheet, "H3").contains("<v>205.236</v>"))
        assertTrue("<v>" !in cell(sheet, "H4"))
        assertEquals(4, styleIndex(cell(sheet, "H4")))
        assertTrue(cell(sheet, "O3").contains("<v>777</v>"))
        assertTrue(cell(sheet, "R3").contains("<v>888</v>"))
        assertTrue(sheet.contains("<f>H3*2</f>"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("<v>123</v>"))
        assertEquals("keep-me", entries.getValue("custom/data.txt"))
    }

    @Test
    fun reusesExistingThreeDecimalVariantOfOriginalStyle() {
        val inputStyles = stylesXml(includeThreeDecimalVariant = true)
        val output = ByteArrayOutputStream()
        QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(includeThreeDecimalStyle = true)),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val entries = unzip(output.toByteArray())
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")

        assertEquals(5, styleIndex(cell(sheet, "H3")))
        assertEquals(inputStyles, entries.getValue("xl/styles.xml"))
        assertEquals(6, cellXfCount(entries.getValue("xl/styles.xml")))
    }

    @Test
    fun ignoresClientCxC24AndPrefersCapturedCj6Variant() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook(clientCaseUnits = "24")),
            output = output,
            items = listOf(
                item(
                    name = "CHÁ CHIMARRÃO GAÚCHO 500G",
                    casePrice = "34.206",
                    units = "6",
                    salesUnit = "CJ",
                    unitPrice = "5.701"
                ),
                item(
                    name = "CHÁ CHIMARRÃO GAÚCHO 500G",
                    casePrice = "138.984",
                    units = "24",
                    salesUnit = "CX",
                    unitPrice = "5.791"
                )
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(1, result.matched)
        assertTrue(cell(sheet, "E3").contains("<v>24</v>"))
        assertTrue(cell(sheet, "H3").contains("<v>34.206</v>"))
        assertFalse(cell(sheet, "H3").contains("138.984"))
    }

    @Test
    fun leavesPriceBlankWhenMoreThanOneCjVariantIsPlausible() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook()),
            output = output,
            items = listOf(
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6", salesUnit = "CJ"),
                item("CHÁ CHIMARRÃO GAÚCHO 500G", "68.412", "12", salesUnit = "CJ")
            ),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(0, result.matched)
        assertEquals(2, result.unmatched)
        assertTrue("<v>" !in cell(sheet, "H3"))
    }

    @Test
    fun stockTwoStaysBlankAndStockThreeFills() {
        fun fill(stock: String): Pair<QuoteWorkbookFiller.FillResult, String> {
            val output = ByteArrayOutputStream()
            val result = QuoteWorkbookFiller.fill(
                input = ByteArrayInputStream(workbook()),
                output = output,
                items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6", stock = stock)),
                conditionDays = 21,
                supplierName = "Vila Nova"
            )
            return result to unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")
        }

        val below = fill("2")
        assertEquals(0, below.first.matched)
        assertTrue("<v>" !in cell(below.second, "H3"))

        val threshold = fill("3")
        assertEquals(1, threshold.first.matched)
        assertTrue(cell(threshold.second, "H3").contains("<v>34.206</v>"))
    }

    @Test
    fun unreadableStockIsRejectedConservatively() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook()),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6", stock = "")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals(0, result.matched)
        assertTrue("<v>" !in cell(sheet, "H3"))
    }

    @Test
    fun ignoresExistingVilaAndValorCaixaColumns() {
        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(workbook()),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals("H", result.targetColumn)
        assertTrue(cell(sheet, "H3").contains("<v>10</v>"))
        assertTrue(cell(sheet, "O3").contains("<v>777</v>"))
        assertTrue(cell(sheet, "R3").contains("<v>888</v>"))
    }

    @Test
    fun inspectsAndFillsRealisticTemplateCoordinatesWithoutUsingExistingVilaColumn() {
        val source = workbook(headerRow = 1706, sheetName = "Cotaçao")
        val template = QuoteWorkbookFiller.inspectTemplate(ByteArrayInputStream(source))

        assertEquals("Cotaçao", template.targetSheet)
        assertEquals("H", template.targetColumn)
        assertEquals("H1706", template.headerCell)

        val output = ByteArrayOutputStream()
        val result = QuoteWorkbookFiller.fill(
            input = ByteArrayInputStream(source),
            output = output,
            items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "34.206", "6")),
            conditionDays = 21,
            supplierName = "Vila Nova"
        )
        val sheet = unzip(output.toByteArray()).getValue("xl/worksheets/sheet1.xml")

        assertEquals("Cotaçao", result.targetSheet)
        assertEquals("H", result.targetColumn)
        assertTrue(cell(sheet, "H1706").contains("<t>Vila</t>"))
        assertTrue(cell(sheet, "H1707").contains("<v>34.206</v>"))
        assertTrue(cell(sheet, "R1706").contains("<v>4</v>"))
        assertTrue(cell(sheet, "R1707").contains("<v>888</v>"))
    }

    @Test
    fun distinguishesExactNomeHeaderFromMissingProductColumn() {
        val source = workbook(includeTextProbeCells = false)
        val inspectionFailure = runCatching {
            QuoteWorkbookFiller.inspectTemplate(ByteArrayInputStream(source))
        }.exceptionOrNull()
        val fillFailure = runCatching {
            QuoteWorkbookFiller.fill(
                input = ByteArrayInputStream(source),
                output = ByteArrayOutputStream(),
                items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
                conditionDays = 21,
                supplierName = "Vila Nova"
            )
        }.exceptionOrNull()

        assertEquals(
            "Encontrei o cabeçalho exato 'nome', mas não consegui identificar a coluna com os nomes dos produtos",
            inspectionFailure?.message
        )
        assertEquals(inspectionFailure?.message, fillFailure?.message)
    }

    @Test
    fun doesNotFallBackWhenExactNomeHeaderIsMissing() {
        val failure = runCatching {
            QuoteWorkbookFiller.fill(
                input = ByteArrayInputStream(workbook(targetHeader = "nome fornecedor")),
                output = ByteArrayOutputStream(),
                items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
                conditionDays = 21,
                supplierName = "Vila Nova"
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("cabeçalho exato 'nome'"))
    }

    @Test
    fun rejectsAnotherSupplierInsteadOfWritingVilaSilently() {
        val failure = runCatching {
            QuoteWorkbookFiller.fill(
                input = ByteArrayInputStream(workbook()),
                output = ByteArrayOutputStream(),
                items = listOf(item("CHÁ CHIMARRÃO GAÚCHO 500G", "10", "12")),
                conditionDays = 21,
                supplierName = "Outro fornecedor"
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("somente o fornecedor Vila Nova"))
    }

    private fun item(
        name: String,
        casePrice: String,
        units: String,
        salesUnit: String = "CJ",
        unitPrice: String = "5.701",
        stock: String = "3"
    ) = QuoteItem(
        id = "$name-$units-$salesUnit",
        capturedAt = 1,
        conditionDays = 7,
        sourcePackage = "ion",
        productCode = "42",
        barcode = "789",
        description = name,
        packaging = "$salesUnit-$units",
        quantity = units,
        unitPrice = unitPrice,
        rawCells = emptyList(),
        pricesByDays = mapOf(7 to unitPrice, 14 to unitPrice, 21 to unitPrice, 28 to unitPrice),
        unitsPerCase = units,
        stock = stock,
        casePricesByDays = mapOf(7 to casePrice, 14 to casePrice, 21 to casePrice, 28 to casePrice),
        salesUnit = salesUnit
    )

    private fun workbook(
        targetHeader: String = "nome",
        clientCaseUnits: String = "24",
        includeThreeDecimalStyle: Boolean = false,
        headerRow: Int = 2,
        sheetName: String = "Cotação",
        includeTextProbeCells: Boolean = true
    ): ByteArray = ByteArrayOutputStream().use { output ->
        val firstDataRow = headerRow + 1
        val secondDataRow = headerRow + 2
        val firstProductCell = if (includeTextProbeCells) {
            """<c r="B$firstDataRow" t="s"><v>1</v></c><c r="D$firstDataRow" t="s"><v>3</v></c>"""
        } else {
            """<c r="B$firstDataRow"><v>101</v></c><c r="D$firstDataRow"><v>0</v></c>"""
        }
        val secondProductCell = if (includeTextProbeCells) {
            """<c r="B$secondDataRow" t="s"><v>2</v></c><c r="D$secondDataRow" t="s"><v>3</v></c>"""
        } else {
            """<c r="B$secondDataRow"><v>102</v></c><c r="D$secondDataRow"><v>0</v></c>"""
        }
        ZipOutputStream(output).use { zip ->
            zip.add(
                "xl/workbook.xml",
                """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="$sheetName" sheetId="1" r:id="rId1"/><sheet name="Referência" sheetId="2" r:id="rId2"/></sheets></workbook>"""
            )
            zip.add(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/></Relationships>"""
            )
            zip.add(
                "xl/sharedStrings.xml",
                """<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>$targetHeader</t></si><si><t>CHÁ CHIMARRÃO GAÚCHO 500G</t></si><si><t>PRODUTO NÃO ENCONTRADO 2KG</t></si><si><t>CX C/</t></si><si><t>VILA</t></si><si><t>VALOR CAIXA</t></si></sst>"""
            )
            zip.add("xl/styles.xml", stylesXml(includeThreeDecimalStyle))
            zip.add(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="$headerRow"><c r="H$headerRow" s="30" t="s"><v>0</v></c><c r="O$headerRow" t="s"><v>5</v></c><c r="R$headerRow" t="s"><v>4</v></c></row><row r="$firstDataRow">$firstProductCell<c r="E$firstDataRow"><v>$clientCaseUnits</v></c><c r="H$firstDataRow" s="4"/><c r="O$firstDataRow"><v>777</v></c><c r="Q$firstDataRow"><f>H$firstDataRow*2</f><v>0</v></c><c r="R$firstDataRow"><v>888</v></c></row><row r="$secondDataRow">$secondProductCell<c r="E$secondDataRow"><v>10</v></c><c r="H$secondDataRow" s="4"/><c r="O$secondDataRow"><v>444</v></c><c r="R$secondDataRow"><v>555</v></c></row></sheetData></worksheet>"""
            )
            zip.add(
                "xl/worksheets/sheet2.xml",
                """<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>123</v></c></row></sheetData></worksheet>"""
            )
            zip.add("custom/data.txt", "keep-me")
        }
        output.toByteArray()
    }

    private fun stylesXml(includeThreeDecimalVariant: Boolean): String {
        val decimalVariant = if (includeThreeDecimalVariant) {
            """<xf numFmtId="166" fontId="7" fillId="3" borderId="2" xfId="9" applyAlignment="1" applyNumberFormat="1"><alignment horizontal="right"/></xf>"""
        } else {
            ""
        }
        val count = if (includeThreeDecimalVariant) 6 else 5
        return """<?xml version="1.0"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="166" formatCode="0.000"/></numFmts><cellXfs count="$count"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="1" borderId="0" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="2" fontId="7" fillId="3" borderId="2" xfId="9" applyAlignment="1"><alignment horizontal="right"/></xf>$decimalVariant</cellXfs></styleSheet>"""
    }

    private fun styleIndex(cellXml: String): Int = Regex("""\bs="(\d+)"""")
        .find(cellXml)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: 0

    private fun cellXf(stylesXml: String, index: Int): Element {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(stylesXml.toByteArray()))
        val cellXfs = document.getElementsByTagNameNS("*", "cellXfs").item(0) as Element
        val styles = buildList {
            var child = cellXfs.firstChild
            while (child != null) {
                if (child is Element && child.localName == "xf") add(child)
                child = child.nextSibling
            }
        }
        return styles[index]
    }

    private fun cellXfCount(stylesXml: String): Int {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(stylesXml.toByteArray()))
        return (document.getElementsByTagNameNS("*", "cellXfs").item(0) as Element)
            .getAttribute("count")
            .toInt()
    }

    private fun cell(sheetXml: String, reference: String): String {
        val start = sheetXml.indexOf("<c r=\"$reference\"")
        require(start >= 0) { "Célula $reference ausente" }
        val regularEnd = sheetXml.indexOf("</c>", start)
        val emptyEnd = sheetXml.indexOf("/>", start)
        return if (emptyEnd >= 0 && (regularEnd < 0 || emptyEnd < regularEnd)) {
            sheetXml.substring(start, emptyEnd + 2)
        } else {
            sheetXml.substring(start, regularEnd + 4)
        }
    }

    private fun ZipOutputStream.add(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
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

package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {
    fun write(
        output: OutputStream,
        items: List<QuoteItem>,
        exportedAt: Long = System.currentTimeMillis(),
        supplierName: String = "Vila Nova",
        conditionDays: Int? = null
    ) {
        require(conditionDays == null || conditionDays in REQUIRED_DAYS) {
            "Prazo sem suporte: $conditionDays"
        }
        val resolvedSupplierName = supplierName.ifBlank { "Vila Nova" }
        val exportedDays = conditionDays?.let(::listOf) ?: REQUIRED_DAYS
        val stockEligibleItems = items.filter(QuoteItem::hasMinimumStock)
        val exportedItems = conditionDays?.let { days ->
            stockEligibleItems.filter { item -> item.casePriceForDays(days) != null }
        } ?: stockEligibleItems
        ZipOutputStream(output.buffered()).use { zip ->
            zip.add("[Content_Types].xml", contentTypes())
            zip.add("_rels/.rels", rootRelationships())
            zip.add("docProps/app.xml", appProperties())
            zip.add("docProps/core.xml", coreProperties(exportedAt))
            zip.add("xl/workbook.xml", workbook(conditionDays))
            zip.add("xl/_rels/workbook.xml.rels", workbookRelationships())
            zip.add("xl/styles.xml", styles())
            zip.add("xl/worksheets/sheet1.xml", quoteSheet(exportedItems, exportedDays))
            zip.add("xl/worksheets/sheet2.xml", pendingSheet(exportedItems, exportedDays))
            zip.add(
                "xl/worksheets/sheet3.xml",
                metadataSheet(exportedItems, exportedAt, resolvedSupplierName, exportedDays, conditionDays)
            )
        }
    }

    private fun quoteSheet(items: List<QuoteItem>, exportedDays: List<Int>): String {
        val headers = buildList {
            add("Produto")
            add("EAN")
            exportedDays.forEach { add("Preço por caixa – $it dias") }
            addAll(
                listOf(
                    "Unidade de venda",
                    "Unidades por caixa",
                    "Estoque",
                    "Código do produto",
                    "Capturado em",
                    "Origem"
                )
            )
        }
        val rows = buildString {
            append(row(1, headers.map { Cell.Text(it, HEADER_STYLE) }))
            items.forEachIndexed { index, item ->
                val cells = buildList {
                    add(Cell.Text(item.description))
                    add(Cell.Text(item.barcode))
                    exportedDays.forEach { days ->
                        add(
                            item.casePriceForDays(days)
                                ?.let { Cell.Number(it.toPlainString(), THREE_DECIMAL_STYLE) }
                                ?: Cell.Text("")
                        )
                    }
                    add(Cell.Text(item.salesUnit))
                    add(
                        item.unitsPerCase.toBigDecimalOrNull()?.let { Cell.Number(it.toPlainString(), NUMBER_STYLE) }
                            ?: Cell.Text(item.unitsPerCase)
                    )
                    add(
                        item.stockValueOrNull()?.let { Cell.Number(it.toPlainString(), THREE_DECIMAL_STYLE) }
                            ?: Cell.Text("")
                    )
                    add(Cell.Text(item.productCode))
                    add(Cell.Text(formatDate(item.capturedAt)))
                    add(Cell.Text(item.sourcePackage))
                }
                append(
                    row(
                        index + 2,
                        cells
                    )
                )
            }
        }
        val columnWidths = buildList {
            add(48)
            add(22)
            repeat(exportedDays.size) { add(22) }
            addAll(listOf(18, 18, 14, 20, 22, 32))
        }
        return worksheetXml(
            rows = rows,
            lastRow = (items.size + 1).coerceAtLeast(1),
            lastColumn = columnName(headers.size),
            columnWidths = columnWidths,
            autoFilter = true
        )
    }

    private fun pendingSheet(items: List<QuoteItem>, exportedDays: List<Int>): String {
        val pending = items.filter { item ->
            item.barcode.isBlank() || item.description.isBlank() || exportedDays.any { item.casePriceForDays(it) == null }
        }
        val rows = buildString {
            append(row(1, listOf("Motivo", "Código", "Código de barras", "Descrição", "Dados lidos").map { Cell.Text(it, HEADER_STYLE) }))
            pending.forEachIndexed { index, item ->
                val reasons = buildList {
                    if (item.barcode.isBlank()) add("sem código de barras")
                    val missingDays = exportedDays.filter { item.casePriceForDays(it) == null }
                    if (missingDays.isNotEmpty()) add("sem preço por caixa: ${missingDays.joinToString("/")} dias")
                    if (item.description.isBlank()) add("sem descrição")
                }.joinToString(", ")
                append(
                    row(
                        index + 2,
                        listOf(
                            Cell.Text(reasons),
                            Cell.Text(item.productCode),
                            Cell.Text(item.barcode),
                            Cell.Text(item.description),
                            Cell.Text(item.rawCells.joinToString(" | "))
                        )
                    )
                )
            }
        }
        return worksheetXml(
            rows = rows,
            lastRow = (pending.size + 1).coerceAtLeast(1),
            lastColumn = "E",
            columnWidths = listOf(28, 16, 22, 48, 70),
            autoFilter = true
        )
    }

    private fun metadataSheet(
        items: List<QuoteItem>,
        exportedAt: Long,
        supplierName: String,
        exportedDays: List<Int>,
        conditionDays: Int?
    ): String {
        val conditionSummary = exportedDays.joinToString(", ") { days ->
            "$days dias: ${items.count { it.casePriceForDays(days) != null }}"
        }
        val pending = items.count { item ->
            item.barcode.isBlank() || item.description.isBlank() || exportedDays.any { item.casePriceForDays(it) == null }
        }
        val source = items.map { it.sourcePackage }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val metadata = listOf(
            "Gerado em" to formatDate(exportedAt),
            "Aplicativo" to "Agiliza Cotação 0.6.4",
            "Quantidade de itens" to items.size.toString(),
            "Itens pendentes" to pending.toString(),
            "Tipo de exportação" to (conditionDays?.let { "Prazo individual de $it dias" } ?: "Consolidado de quatro prazos"),
            "Condições" to conditionSummary,
            "Fornecedor" to supplierName,
            "Origem" to source,
            "Regra de estoque" to "Somente estoque numérico maior ou igual a 3; ausente/ilegível é excluído.",
            "Regra do preço" to "Preço por caixa é o Pço Tabela/Valor lido no detalhe; não há nova multiplicação.",
            "Regra dos prazos" to "Preços lidos diretamente no ION; sem cálculo de juros.",
            "Observação" to "Revise os valores antes de enviar a cotação."
        )
        val rows = buildString {
            append(row(1, listOf(Cell.Text("Campo", HEADER_STYLE), Cell.Text("Valor", HEADER_STYLE))))
            metadata.forEachIndexed { index, entry ->
                append(row(index + 2, listOf(Cell.Text(entry.first), Cell.Text(entry.second))))
            }
        }
        return worksheetXml(
            rows = rows,
            lastRow = metadata.size + 1,
            lastColumn = "B",
            columnWidths = listOf(26, 80),
            autoFilter = false
        )
    }

    private fun worksheetXml(
        rows: String,
        lastRow: Int,
        lastColumn: String,
        columnWidths: List<Int>,
        autoFilter: Boolean
    ): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <dimension ref="A1:$lastColumn$lastRow"/>
          <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
          <sheetFormatPr defaultRowHeight="18"/>
          <cols>${columnWidths.mapIndexed { index, width -> "<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>" }.joinToString("")}</cols>
          <sheetData>$rows</sheetData>
          ${if (autoFilter) "<autoFilter ref=\"A1:$lastColumn$lastRow\"/>" else ""}
        </worksheet>""".trimIndent()

    private fun row(number: Int, cells: List<Cell>): String = buildString {
        append("<row r=\"$number\">")
        cells.forEachIndexed { index, cell ->
            val reference = "${columnName(index + 1)}$number"
            when (cell) {
                is Cell.Text -> append(
                    "<c r=\"$reference\" t=\"inlineStr\" s=\"${cell.style}\"><is><t xml:space=\"preserve\">${escape(cell.value)}</t></is></c>"
                )
                is Cell.Number -> append("<c r=\"$reference\" s=\"${cell.style}\"><v>${escape(cell.value)}</v></c>")
            }
        }
        append("</row>")
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
        </Types>""".trimIndent()

    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>""".trimIndent()

    private fun workbook(conditionDays: Int?) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="${conditionDays?.let { "$it dias" } ?: "Cotação"}" sheetId="1" r:id="rId1"/>
            <sheet name="Pendências" sheetId="2" r:id="rId2"/>
            <sheet name="Metadados" sheetId="3" r:id="rId3"/>
          </sheets>
          <calcPr calcId="191029"/>
        </workbook>""".trimIndent()

    private fun workbookRelationships() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
          <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>""".trimIndent()

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <numFmts count="2"><numFmt numFmtId="164" formatCode="&quot;R$&quot; #,##0.00"/><numFmt numFmtId="165" formatCode="0.###"/></numFmts>
          <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font></fonts>
          <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF10233F"/><bgColor indexed="64"/></patternFill></fill></fills>
          <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <cellXfs count="6">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
            <xf numFmtId="0" fontId="1" fillId="1" borderId="0" xfId="0" applyFill="1" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
            <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
            <xf numFmtId="1" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
            <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
          </cellXfs>
          <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
        </styleSheet>""".trimIndent()

    private fun coreProperties(exportedAt: Long): String {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timestamp = utc.format(Date(exportedAt))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:creator>Agiliza Cotação</dc:creator><cp:lastModifiedBy>Agiliza Cotação</cp:lastModifiedBy>
              <dcterms:created xsi:type="dcterms:W3CDTF">$timestamp</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$timestamp</dcterms:modified>
            </cp:coreProperties>""".trimIndent()
    }

    private fun appProperties() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
          <Application>Agiliza Cotação</Application><AppVersion>0.1</AppVersion>
        </Properties>""".trimIndent()

    private fun ZipOutputStream.add(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private val REQUIRED_DAYS = listOf(7, 14, 21, 28)

    private fun columnName(number: Int): String {
        var value = number
        val name = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            name.append(('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return name.reverse().toString()
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (char.code in 0x20..0xD7FF || char == '\n' || char == '\r' || char == '\t') append(char)
            }
        }
    }

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR"))
        .format(Date(timestamp))

    private sealed interface Cell {
        data class Text(val value: String, val style: Int = DEFAULT_STYLE) : Cell
        data class Number(val value: String, val style: Int) : Cell
    }

    private const val DEFAULT_STYLE = 0
    private const val HEADER_STYLE = 1
    private const val CURRENCY_STYLE = 2
    private const val NUMBER_STYLE = 3
    private const val INTEGER_STYLE = 4
    private const val THREE_DECIMAL_STYLE = 5
}

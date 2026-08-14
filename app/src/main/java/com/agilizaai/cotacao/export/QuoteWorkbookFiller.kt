package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Preserves the supplied XLSX package and changes only matched price cells. */
object QuoteWorkbookFiller {
    data class FillResult(
        val targetSheet: String,
        val targetColumn: String,
        val matched: Int,
        val unmatched: Int,
        val rowsRead: Int
    )

    fun fill(
        input: InputStream,
        output: OutputStream,
        items: List<QuoteItem>,
        conditionDays: Int,
        supplierName: String
    ): FillResult {
        require(conditionDays in listOf(7, 14, 21, 28)) { "Prazo inválido" }
        require(items.isNotEmpty()) { "Não há tabela capturada" }
        val entries = readZip(input)
        val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheets = workbookSheets(entries)
        var candidate: Candidate? = null
        for (sheet in sheets) {
            val bytes = entries[sheet.path] ?: continue
            val document = parseXml(bytes)
            val target = findTarget(document, sharedStrings, sheet.name, supplierName) ?: continue
            val found = Candidate(sheet, document, target)
            val current = candidate
            if (current == null || found.target.priority < current.target.priority ||
                (found.target.priority == current.target.priority && found.target.rowsPotential > current.target.rowsPotential)
            ) {
                candidate = found
            }
            if (found.target.priority == 0) break
        }
        val selected = candidate ?: error("Não encontrei a coluna '$supplierName' nem uma coluna VALOR CAIXA/CUSTO")

        var matched = 0
        var unmatched = 0
        var rowsRead = 0
        val rows = selected.document.elements("row")
            .filter { it.attr("r").toIntOrNull()?.let { row -> row > selected.target.headerRow } == true }
        rows.forEach { row ->
            val rowNumber = row.attr("r").toIntOrNull() ?: return@forEach
            val nameCell = row.cell(selected.target.productColumn) ?: return@forEach
            val productName = readCell(nameCell, sharedStrings).trim()
            if (productName.length < 4 || productName.equals("Z", true)) return@forEach
            rowsRead++
            val expectedUnits = readExpectedUnits(row, selected.target.caseColumn, sharedStrings)
            val match = ProductNameMatcher.find(productName, items, expectedUnits)
            val price = match?.item?.casePriceForDays(conditionDays)
            if (price == null) {
                unmatched++
                return@forEach
            }
            val targetCell = row.getOrCreateCell(selected.target.priceColumn, rowNumber, selected.document)
            targetCell.writeNumber(price.stripTrailingZeros().toPlainString())
            matched++
        }

        check(rowsRead > 0) { "A planilha não possui produtos abaixo do cabeçalho" }
        entries[selected.sheet.path] = xmlBytes(selected.document)
        writeZip(entries, output)
        return FillResult(
            targetSheet = selected.sheet.name,
            targetColumn = columnName(selected.target.priceColumn),
            matched = matched,
            unmatched = unmatched,
            rowsRead = rowsRead
        )
    }

    private data class WorkbookSheet(val name: String, val path: String)
    private data class Target(
        val headerRow: Int,
        val productColumn: Int,
        val priceColumn: Int,
        val caseColumn: Int?,
        val priority: Int,
        val rowsPotential: Int
    )
    private data class Candidate(val sheet: WorkbookSheet, val document: Document, val target: Target)

    private fun findTarget(
        document: Document,
        sharedStrings: List<String>,
        sheetName: String,
        supplierName: String
    ): Target? {
        val rows = document.elements("row")
        val supplier = ProductNameMatcher.normalize(supplierName)
        val acceptedSupplierNames = acceptedSupplierNames(supplier)
        var best: Target? = null
        rows.forEach { row ->
            val rowNumber = row.attr("r").toIntOrNull() ?: return@forEach
            val values = row.childElements("c").mapNotNull { cell ->
                val column = cell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull() ?: return@mapNotNull null
                column to ProductNameMatcher.normalize(readCell(cell, sharedStrings))
            }.filter { it.second.isNotBlank() }
            if (values.isEmpty()) return@forEach

            // Prefer the official name when both headers happen to exist. Vila Nova's
            // quotation model abbreviates its own column to exactly "VILA"; no other
            // partial/prefix matching is allowed here.
            val supplierColumn = values.firstOrNull { (_, text) -> text == supplier }?.first
                ?: values.firstOrNull { (_, text) -> text in acceptedSupplierNames }?.first
            val caseColumn = values.firstOrNull { (_, text) -> isCaseQuantityHeader(text) }?.first
            if (supplierColumn != null) {
                val productColumn = findProductColumn(document, sharedStrings, rowNumber, preferred = 2)
                if (productColumn != null) {
                    val potential = rows.count { it.attr("r").toIntOrNull()?.let { number -> number > rowNumber } == true }
                    val target = Target(
                        rowNumber,
                        productColumn,
                        supplierColumn,
                        caseColumn,
                        priority = 0,
                        rowsPotential = potential
                    )
                    if (best == null || target.rowsPotential > best!!.rowsPotential) best = target
                }
            }

            val productHeader = values.firstOrNull { (_, text) ->
                text == "PRODUTO" || text.contains("DESCRICAO") || text == "ITEM"
            }?.first
            val priceHeader = values.firstOrNull { (_, text) ->
                text.contains("VALOR CAIXA") || text == "CUSTO" || text.contains("PRECO CAIXA")
            }?.first
            if (productHeader != null && priceHeader != null) {
                val sheetMatches = ProductNameMatcher.normalize(sheetName) in acceptedSupplierNames
                val target = Target(
                    rowNumber,
                    productHeader,
                    priceHeader,
                    caseColumn,
                    priority = if (sheetMatches) 1 else 2,
                    rowsPotential = 0
                )
                if (best == null || target.priority < best!!.priority) best = target
            }
        }
        return best
    }

    private fun acceptedSupplierNames(normalizedSupplier: String): Set<String> =
        if (normalizedSupplier == "VILA NOVA") setOf("VILA NOVA", "VILA")
        else setOf(normalizedSupplier)

    private fun isCaseQuantityHeader(text: String): Boolean =
        text == "CX" ||
            text == "CX C" ||
            text.startsWith("CX C ") ||
            text == "QTD CX" ||
            text == "QTDE CX" ||
            text.contains("QTD CAIXA") ||
            text.contains("QTDE CAIXA")

    private fun readExpectedUnits(
        row: Element,
        caseColumn: Int?,
        sharedStrings: List<String>
    ): String? {
        if (caseColumn != null) {
            return row.cell(caseColumn)
                ?.let { readCell(it, sharedStrings).trim() }
                ?.takeIf { ProductNameMatcher.parseUnits(it) != null }
        }

        // Some quotation models repeat "CX C/" in every product row and put
        // the actual quantity in the immediately adjacent cell (for example D/E).
        // Accept exactly one distinct valid quantity; conflicting labels are ambiguous.
        val quantities = row.childElements("c").mapNotNull { labelCell ->
            val label = ProductNameMatcher.normalize(readCell(labelCell, sharedStrings))
            if (!isCaseQuantityHeader(label)) return@mapNotNull null
            val labelColumn = labelCell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull()
                ?: return@mapNotNull null
            row.cell(labelColumn + 1)
                ?.let { readCell(it, sharedStrings).trim() }
                ?.takeIf { ProductNameMatcher.parseUnits(it) != null }
        }.distinctBy { ProductNameMatcher.parseUnits(it) }
        return quantities.singleOrNull()
    }

    private fun findProductColumn(
        document: Document,
        sharedStrings: List<String>,
        headerRow: Int,
        preferred: Int
    ): Int? {
        val probeRows = document.elements("row")
            .filter { it.attr("r").toIntOrNull()?.let { row -> row in (headerRow + 1)..(headerRow + 40) } == true }
        val counts = linkedMapOf<Int, Int>()
        probeRows.forEach { row ->
            row.childElements("c").forEach { cell ->
                val column = cell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull() ?: return@forEach
                val text = readCell(cell, sharedStrings)
                if (text.count(Char::isLetter) >= 4) counts[column] = (counts[column] ?: 0) + 1
            }
        }
        return if ((counts[preferred] ?: 0) >= 2) preferred else counts.maxByOrNull { it.value }?.key
    }

    private fun workbookSheets(entries: Map<String, ByteArray>): List<WorkbookSheet> {
        val workbook = parseXml(entries["xl/workbook.xml"] ?: error("XLSX sem workbook.xml"))
        val relationships = parseXml(entries["xl/_rels/workbook.xml.rels"] ?: error("XLSX sem relacionamentos"))
        val targets = relationships.elements("Relationship").associate { relationship ->
            relationship.attr("Id") to relationship.attr("Target")
        }
        return workbook.elements("sheet").mapNotNull { sheet ->
            val relationshipId = sheet.getAttributeNS(REL_NS, "id").ifBlank { sheet.attr("r:id") }
            val target = targets[relationshipId] ?: return@mapNotNull null
            val normalized = if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("./")}".replace("xl/xl/", "xl/")
            WorkbookSheet(sheet.attr("name"), normalized)
        }
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        return parseXml(bytes).elements("si").map { item ->
            item.getElementsByTagNameNS("*", "t").let { nodes ->
                buildString { for (index in 0 until nodes.length) append(nodes.item(index).textContent) }
            }
        }
    }

    private fun readCell(cell: Element, sharedStrings: List<String>): String {
        val inline = cell.getElementsByTagNameNS("*", "is")
        if (inline.length > 0) return inline.item(0).textContent.orEmpty()
        val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
        return if (cell.attr("t") == "s") sharedStrings.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value
    }

    private fun Element.writeNumber(value: String) {
        removeAttribute("t")
        childElements("f").forEach { removeChild(it) }
        childElements("is").forEach { removeChild(it) }
        childElements("v").forEach { removeChild(it) }
        val number = ownerDocument.createElementNS(SHEET_NS, "v")
        number.textContent = value
        appendChild(number)
    }

    private fun Element.getOrCreateCell(column: Int, rowNumber: Int, document: Document): Element {
        cell(column)?.let { return it }
        val created = document.createElementNS(SHEET_NS, "c")
        created.setAttribute("r", "${columnName(column)}$rowNumber")
        val cells = childElements("c")
        val next = cells.firstOrNull { cell ->
            val existing = cell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull() ?: Int.MAX_VALUE
            existing > column
        }
        if (next != null) insertBefore(created, next) else appendChild(created)
        return created
    }

    private fun Element.cell(column: Int): Element? = childElements("c").firstOrNull { cell ->
        cell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull() == column
    }

    private fun readZip(input: InputStream): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun writeZip(entries: Map<String, ByteArray>, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun parseXml(bytes: ByteArray): Document = documentBuilderFactory()
        .newDocumentBuilder()
        .parse(ByteArrayInputStream(bytes))

    private fun xmlBytes(document: Document): ByteArray = ByteArrayOutputStream().use { output ->
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.INDENT, "no")
        }.transform(DOMSource(document), StreamResult(output))
        output.toByteArray()
    }

    private fun documentBuilderFactory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }

    private fun Document.elements(localName: String): List<Element> =
        getElementsByTagNameNS("*", localName).let { list ->
            buildList { for (index in 0 until list.length) (list.item(index) as? Element)?.let(::add) }
        }

    private fun Element.childElements(localName: String): List<Element> = buildList {
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element && child.localName == localName) add(child)
            child = child.nextSibling
        }
    }

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private fun String.columnNumberOrNull(): Int? {
        if (isBlank() || any { it !in 'A'..'Z' }) return null
        var result = 0
        forEach { result = result * 26 + (it - 'A' + 1) }
        return result
    }

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            result.append(('A'.code + (value - 1) % 26).toChar())
            value = (value - 1) / 26
        }
        return result.reverse().toString()
    }

    private const val SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}

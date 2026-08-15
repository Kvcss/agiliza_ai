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
    data class TemplateInfo(
        val targetSheet: String,
        val targetColumn: String,
        val headerCell: String
    )

    data class FillResult(
        val targetSheet: String,
        val targetColumn: String,
        val matched: Int,
        val unmatched: Int,
        val rowsRead: Int
    )

    fun inspectTemplate(input: InputStream): TemplateInfo = locateTemplate(readZip(input))
        .candidate
        .toTemplateInfo()

    fun fill(
        input: InputStream,
        output: OutputStream,
        items: List<QuoteItem>,
        conditionDays: Int,
        supplierName: String
    ): FillResult {
        require(conditionDays in listOf(7, 14, 21, 28)) { "Prazo inválido" }
        require(items.isNotEmpty()) { "Não há tabela capturada" }
        require(ProductNameMatcher.normalize(supplierName) == FIXED_SUPPLIER_NAME) {
            "Esta modalidade preenche somente o fornecedor Vila Nova"
        }
        val entries = readZip(input)
        val located = locateTemplate(entries)
        val selected = located.candidate
        val sharedStrings = located.sharedStrings
        val priceStyles = entries[STYLES_PATH]?.let(::parsePriceStyles)

        val headerCell = selected.document.elements("row")
            .firstOrNull { it.attr("r").toIntOrNull() == selected.target.headerRow }
            ?.cell(selected.target.priceColumn)
            ?: error("A célula do cabeçalho 'nome' não está disponível")
        headerCell.writeText(OUTPUT_HEADER)
        val eligibleItems = items.filter(QuoteItem::hasMinimumStock)

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
            val match = ProductNameMatcher.find(
                productName = productName,
                items = eligibleItems,
                variantPolicy = ProductNameMatcher.VariantPolicy.PREFER_SINGLE_CJ
            )
            val price = match?.item?.casePriceForDays(conditionDays)
            if (price == null) {
                unmatched++
                return@forEach
            }
            val targetCell = row.getOrCreateCell(selected.target.priceColumn, rowNumber, selected.document)
            priceStyles?.let { styles ->
                targetCell.setAttribute("s", styles.threeDecimalStyle(targetCell.attr("s")).toString())
            }
            targetCell.writeNumber(price.stripTrailingZeros().toPlainString())
            matched++
        }

        check(rowsRead > 0) { "A planilha não possui produtos abaixo do cabeçalho" }
        entries[selected.sheet.path] = xmlBytes(selected.document)
        if (priceStyles?.changed == true) entries[STYLES_PATH] = xmlBytes(priceStyles.document)
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
        val priority: Int,
        val rowsPotential: Int
    )
    private data class Candidate(val sheet: WorkbookSheet, val document: Document, val target: Target)
    private data class LocatedTemplate(val candidate: Candidate, val sharedStrings: List<String>)
    private data class TargetSearch(val target: Target?, val exactHeaderFound: Boolean)
    private data class CellText(val column: Int, val normalized: String)
    private data class PriceStyles(
        val document: Document,
        val cellXfs: Element,
        val numberFormatId: String,
        val cache: MutableMap<Int, Int> = mutableMapOf(),
        var changed: Boolean = false
    )

    private fun locateTemplate(entries: Map<String, ByteArray>): LocatedTemplate {
        val sharedStrings = parseSharedStrings(entries["xl/sharedStrings.xml"])
        var candidate: Candidate? = null
        var exactHeaderFound = false
        for (sheet in workbookSheets(entries)) {
            val bytes = entries[sheet.path] ?: continue
            val document = parseXml(bytes)
            val search = findTarget(document, sharedStrings, sheet.name)
            exactHeaderFound = exactHeaderFound || search.exactHeaderFound
            val target = search.target ?: continue
            val found = Candidate(sheet, document, target)
            val current = candidate
            if (current == null || found.target.priority < current.target.priority ||
                (found.target.priority == current.target.priority && found.target.rowsPotential > current.target.rowsPotential)
            ) {
                candidate = found
            }
            if (found.target.priority == 0) break
        }
        val selected = candidate ?: if (exactHeaderFound) {
            error("Encontrei o cabeçalho exato 'nome', mas não consegui identificar a coluna com os nomes dos produtos")
        } else {
            error("Não encontrei o cabeçalho exato 'nome' na aba de cotação")
        }
        return LocatedTemplate(selected, sharedStrings)
    }

    private fun Candidate.toTemplateInfo() = TemplateInfo(
        targetSheet = sheet.name,
        targetColumn = columnName(target.priceColumn),
        headerCell = "${columnName(target.priceColumn)}${target.headerRow}"
    )

    private fun findTarget(
        document: Document,
        sharedStrings: List<String>,
        sheetName: String
    ): TargetSearch {
        val rows = document.elements("row")
        var best: Target? = null
        var exactHeaderFound = false
        rows.forEach { row ->
            val rowNumber = row.attr("r").toIntOrNull() ?: return@forEach
            val values = row.childElements("c").mapNotNull { cell ->
                val column = cell.attr("r").takeWhile(Char::isLetter).columnNumberOrNull() ?: return@mapNotNull null
                val raw = readCell(cell, sharedStrings).trim()
                CellText(column, ProductNameMatcher.normalize(raw))
            }.filter { it.normalized.isNotBlank() }
            if (values.isEmpty()) return@forEach

            // The client workbook reserves the exact header "nome" for this supplier.
            // Existing supplier columns (including a pre-existing VILA column) belong
            // to other quotations and must never be selected or modified.
            val supplierHeader = values.firstOrNull { it.normalized == INPUT_HEADER }
            if (supplierHeader != null) {
                exactHeaderFound = true
                val productColumn = findProductColumn(document, sharedStrings, rowNumber, preferred = 2)
                if (productColumn != null) {
                    val potential = rows.count { it.attr("r").toIntOrNull()?.let { number -> number > rowNumber } == true }
                    val target = Target(
                        rowNumber,
                        productColumn,
                        supplierHeader.column,
                        priority = if (ProductNameMatcher.normalize(sheetName).contains("COTACAO")) 0 else 1,
                        rowsPotential = potential
                    )
                    if (best == null || target.priority < best!!.priority ||
                        (target.priority == best!!.priority && target.rowsPotential > best!!.rowsPotential)
                    ) {
                        best = target
                    }
                }
            }
        }
        return TargetSearch(best, exactHeaderFound)
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

    private fun parsePriceStyles(bytes: ByteArray): PriceStyles? {
        val document = parseXml(bytes)
        val styleSheet = document.documentElement ?: return null
        val cellXfs = styleSheet.childElements("cellXfs").firstOrNull() ?: return null
        if (cellXfs.childElements("xf").isEmpty()) return null

        val customFormats = styleSheet.childElements("numFmts").firstOrNull()
        val existingFormat = customFormats?.childElements("numFmt")
            ?.firstOrNull { it.attr("formatCode").replace("\\", "") == THREE_DECIMAL_FORMAT }
        val formatId = existingFormat?.attr("numFmtId")?.takeIf(String::isNotBlank)
            ?: createThreeDecimalFormat(document, styleSheet, customFormats)
        return PriceStyles(document, cellXfs, formatId, changed = existingFormat == null)
    }

    private fun createThreeDecimalFormat(
        document: Document,
        styleSheet: Element,
        existingFormats: Element?
    ): String {
        val numFmts = existingFormats ?: document.createElementNS(SHEET_NS, "numFmts").also { created ->
            val firstStyleSection = styleSheet.childElements().firstOrNull { it.localName != "numFmts" }
            if (firstStyleSection != null) styleSheet.insertBefore(created, firstStyleSection) else styleSheet.appendChild(created)
        }
        val usedIds = numFmts.childElements("numFmt").mapNotNull { it.attr("numFmtId").toIntOrNull() }.toSet()
        val formatId = generateSequence(FIRST_CUSTOM_NUMBER_FORMAT_ID) { it + 1 }
            .first { it !in usedIds }
        val format = document.createElementNS(SHEET_NS, "numFmt")
        format.setAttribute("numFmtId", formatId.toString())
        format.setAttribute("formatCode", THREE_DECIMAL_FORMAT)
        numFmts.appendChild(format)
        numFmts.setAttribute("count", numFmts.childElements("numFmt").size.toString())
        return formatId.toString()
    }

    private fun PriceStyles.threeDecimalStyle(originalStyle: String): Int {
        val styles = cellXfs.childElements("xf")
        val requestedIndex = originalStyle.toIntOrNull() ?: 0
        val baseIndex = requestedIndex.takeIf { it in styles.indices } ?: 0
        cache[baseIndex]?.let { return it }
        val base = styles[baseIndex]
        val existingIndex = styles.indexOfFirst { candidate ->
            candidate.attr("numFmtId") == numberFormatId &&
                candidate.attr("applyNumberFormat") == "1" &&
                candidate.sameBaseStyleAs(base)
        }
        if (existingIndex >= 0) {
            cache[baseIndex] = existingIndex
            return existingIndex
        }

        val created = base.cloneNode(true) as Element
        created.setAttribute("numFmtId", numberFormatId)
        created.setAttribute("applyNumberFormat", "1")
        cellXfs.appendChild(created)
        val createdIndex = cellXfs.childElements("xf").lastIndex
        cellXfs.setAttribute("count", (createdIndex + 1).toString())
        cache[baseIndex] = createdIndex
        changed = true
        return createdIndex
    }

    private fun Element.sameBaseStyleAs(other: Element): Boolean =
        styleAttributes() == other.styleAttributes() &&
            childElements().map { it.structuralSignature() } ==
            other.childElements().map { it.structuralSignature() }

    private fun Element.styleAttributes(): Map<String, String> = buildMap {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if (attribute.nodeName !in NUMBER_FORMAT_STYLE_ATTRIBUTES) put(attribute.nodeName, attribute.nodeValue)
        }
    }

    private fun Element.structuralSignature(): String = buildString {
        append(localName)
        append('[')
        val attributes = attributes
        val values = buildList {
            for (index in 0 until attributes.length) {
                val attribute = attributes.item(index)
                add(attribute.nodeName to attribute.nodeValue)
            }
        }.sortedBy { it.first }
        values.forEach { (name, value) -> append(name).append('=').append(value).append(';') }
        append(']')
        childElements().forEach { append(it.structuralSignature()) }
        val text = textContent.orEmpty().trim()
        if (childElements().isEmpty() && text.isNotEmpty()) append('{').append(text).append('}')
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

    /** Changes only the cell contents and keeps its existing style/position attributes. */
    private fun Element.writeText(value: String) {
        setAttribute("t", "inlineStr")
        childElements("f").forEach { removeChild(it) }
        childElements("is").forEach { removeChild(it) }
        childElements("v").forEach { removeChild(it) }
        val inline = ownerDocument.createElementNS(SHEET_NS, "is")
        val text = ownerDocument.createElementNS(SHEET_NS, "t")
        text.textContent = value
        inline.appendChild(text)
        appendChild(inline)
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

    private fun Element.childElements(): List<Element> = buildList {
        var child: Node? = firstChild
        while (child != null) {
            if (child is Element) add(child)
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
    private const val INPUT_HEADER = "NOME"
    private const val OUTPUT_HEADER = "Vila"
    private const val FIXED_SUPPLIER_NAME = "VILA NOVA"
    private const val STYLES_PATH = "xl/styles.xml"
    private const val THREE_DECIMAL_FORMAT = "0.000"
    private const val FIRST_CUSTOM_NUMBER_FORMAT_ID = 164
    private val NUMBER_FORMAT_STYLE_ATTRIBUTES = setOf("numFmtId", "applyNumberFormat")
}

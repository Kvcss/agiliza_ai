package com.agilizaai.cotacao.capture

import com.agilizaai.cotacao.data.QuoteItem
import com.agilizaai.cotacao.data.toBrazilianDecimalOrNull
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Reads both conventional horizontal tables and the vertical product cards used by Blink Mobile.
 *
 * Blink exposes one field per visual line (Produto, Cód., Emb., UN and EAN), so grouping only by
 * Y coordinate loses the relationship between the fields. Card parsing therefore runs first and
 * uses each Cód. line as a stable anchor. A partially visible card is deliberately ignored until a
 * later scroll exposes its description, unit price and EAN together.
 */
class RowParser(private val rowTolerancePx: Int) {
    fun findConditionColumnX(nodes: List<NodeSnapshot>, conditionDays: Int): Int? =
        findHeaderX(nodes, conditionDaysHeaderRegex(conditionDays))

    fun parse(
        nodes: List<NodeSnapshot>,
        conditionDays: Int,
        sourcePackage: String,
        capturedAt: Long = System.currentTimeMillis(),
        conditionColumnX: Int? = null
    ): List<QuoteItem> {
        if (nodes.isEmpty()) return emptyList()

        val blinkCards = parseBlinkCards(nodes, conditionDays, sourcePackage, capturedAt)
        if (blinkCards.isNotEmpty()) return distinctProducts(blinkCards)

        val preferredPriceX = conditionColumnX ?: findConditionColumnX(nodes, conditionDays)
        val quantityX = findHeaderX(nodes, QUANTITY_HEADER)
        val rows = groupByVisualRow(nodes).mapNotNull { row ->
            parseTableRow(row, conditionDays, sourcePackage, capturedAt, preferredPriceX, quantityX)
        }
        return distinctProducts(rows)
    }

    private fun parseBlinkCards(
        nodes: List<NodeSnapshot>,
        conditionDays: Int,
        sourcePackage: String,
        capturedAt: Long
    ): List<QuoteItem> {
        val anchors = findBlinkCardAnchors(nodes)
        if (anchors.isEmpty()) return emptyList()

        return anchors.mapIndexedNotNull { index, anchor ->
            val previous = anchors.getOrNull(index - 1)
            val next = anchors.getOrNull(index + 1)
            val startY = previous?.let { (it.node.centerY + anchor.node.centerY) / 2 }
                ?: (anchor.node.top - firstCardLookBehindPx()).coerceAtLeast(0)
            val endY = next?.let { (anchor.node.centerY + it.node.centerY) / 2 }
                ?: (nodes.maxOfOrNull { it.bottom } ?: anchor.node.bottom) + rowTolerancePx
            val cardNodes = nodes
                .asSequence()
                .filter { it.width > 0 && it.height > 0 && it.text.length <= MAX_CARD_CELL_LENGTH }
                .filter { it.centerY in startY until endY }
                .sortedWith(compareBy<NodeSnapshot> { it.top }.thenBy { it.left })
                .distinctBy { "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}" }
                .toList()

            parseBlinkCard(cardNodes, anchor, conditionDays, sourcePackage, capturedAt)
        }
    }

    private fun findBlinkCardAnchors(nodes: List<NodeSnapshot>): List<CardAnchor> {
        val candidates = nodes.mapNotNull { node ->
            if (node.text.length > MAX_CARD_CELL_LENGTH || node.width <= 0 || node.height <= 0) {
                return@mapNotNull null
            }
            val productCode = extractFirst(node.text, PRODUCT_CODE_FIELD)
                ?: findSplitProductCode(node, nodes)
                ?: ""
            if (productCode.isBlank()) null else CardAnchor(node, productCode)
        }.sortedBy { it.node.centerY }

        val anchors = mutableListOf<CardAnchor>()
        candidates.forEach { candidate ->
            val duplicateIndex = anchors.indexOfLast { existing ->
                existing.productCode.equals(candidate.productCode, ignoreCase = true) &&
                    abs(existing.node.centerY - candidate.node.centerY) <= duplicateAnchorTolerancePx()
            }
            if (duplicateIndex < 0) {
                anchors += candidate
            } else if (anchorSpecificity(candidate.node) < anchorSpecificity(anchors[duplicateIndex].node)) {
                // Accessibility trees often expose both a whole-card description and its TextView.
                // The shortest matching node is normally the actual Cód. line and has the best Y.
                anchors[duplicateIndex] = candidate
            }
        }
        return anchors.sortedBy { it.node.centerY }
    }

    private fun parseBlinkCard(
        cardNodes: List<NodeSnapshot>,
        anchor: CardAnchor,
        conditionDays: Int,
        sourcePackage: String,
        capturedAt: Long
    ): QuoteItem? {
        if (cardNodes.isEmpty()) return null
        val texts = cardNodes.map { it.text }.distinct()
        val fieldNodes = cardNodes.filter { node ->
            node.centerY >= anchor.node.centerY - rowTolerancePx || node === anchor.node
        }
        val flattenedFields = fieldNodes.joinToString(" ") { it.text }

        val productCode = anchor.productCode
        val barcode = extractValidBarcode(cardNodes, anchor) ?: return null
        val unitPrice = extractField(fieldNodes, flattenedFields, UNIT_PRICE_FIELD)
            ?.toBrazilianDecimalOrNull()
            ?.toPlainString()
            ?: return null
        val packaging = extractField(fieldNodes, flattenedFields, PACKAGING_FIELD).orEmpty()
        val quantity = PACK_QUANTITY.find(packaging)?.groupValues?.get(1)
            ?.toBrazilianDecimalOrNull()
            ?.stripTrailingZeros()
            ?.toPlainString()
            .orEmpty()
        val description = findBlinkDescription(cardNodes, anchor) ?: return null

        val stableSource = listOf(
            barcode,
            productCode,
            description,
            unitPrice,
            conditionDays.toString()
        ).joinToString("|")

        return QuoteItem(
            id = sha256(stableSource),
            capturedAt = capturedAt,
            conditionDays = conditionDays,
            sourcePackage = sourcePackage,
            productCode = productCode.trim(),
            barcode = barcode,
            description = description,
            packaging = packaging,
            quantity = quantity,
            unitPrice = unitPrice,
            rawCells = texts
        )
    }

    private fun extractValidBarcode(cardNodes: List<NodeSnapshot>, anchor: CardAnchor): String? {
        val eanNodes = cardNodes.filter { node ->
            node.centerY >= anchor.node.centerY - rowTolerancePx ||
                (node === anchor.node && EAN_FIELD.containsMatchIn(node.text))
        }
        val candidates = buildList {
            eanNodes.forEach { node ->
                extractFirst(node.text, EAN_FIELD)?.let(::add)
            }
            extractFirst(eanNodes.joinToString(" ") { it.text }, EAN_FIELD)?.let(::add)
        }
        return candidates
            .map(::digitsOnly)
            .firstOrNull { it.length in BARCODE_LENGTHS }
    }

    private fun extractField(
        cardNodes: List<NodeSnapshot>,
        flattened: String,
        pattern: Regex
    ): String? {
        cardNodes.forEach { node ->
            extractFirst(node.text, pattern)?.let { return it }
        }
        return extractFirst(flattened, pattern)
    }

    private fun findBlinkDescription(cardNodes: List<NodeSnapshot>, anchor: CardAnchor): String? {
        // In the native Blink layout the product title is the closest useful text above Cód.
        val directTitle = cardNodes
            .asSequence()
            .filter { it.centerY <= anchor.node.centerY + rowTolerancePx }
            .filter { isBlinkDescription(it.text) }
            .maxByOrNull { it.centerY }
            ?.text
            ?.trim()
        if (directTitle != null) return directTitle

        // Some implementations expose the complete card as one accessibility description.
        cardNodes.forEach { node ->
            val codeMatch = PRODUCT_CODE_FIELD.find(node.text) ?: return@forEach
            val prefix = node.text.substring(0, codeMatch.range.first).trim(' ', '-', '|', '\n', '\r')
            if (isBlinkDescription(prefix)) return prefix
        }
        return null
    }

    private fun findSplitProductCode(labelNode: NodeSnapshot, nodes: List<NodeSnapshot>): String? {
        if (!PRODUCT_CODE_LABEL.matches(labelNode.text.trim())) return null
        return nodes
            .asSequence()
            .filter { it !== labelNode }
            .filter { abs(it.centerY - labelNode.centerY) <= maxOf(rowTolerancePx * 2, 36) }
            .filter { it.centerX >= labelNode.centerX }
            .map { it to it.text.trim() }
            .filter { (_, text) -> isProductCode(text) }
            .minByOrNull { (node, _) ->
                abs(node.centerY - labelNode.centerY) * 4 + abs(node.left - labelNode.right)
            }
            ?.second
    }

    private fun isBlinkDescription(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 3..MAX_DESCRIPTION_LENGTH || trimmed.count(Char::isLetter) < 3) return false
        if (isHeaderOrChrome(trimmed)) return false
        if (BLINK_FIELD_LABEL.containsMatchIn(trimmed)) return false
        if (PRICE_ANYWHERE.containsMatchIn(trimmed)) return false
        return true
    }

    private fun groupByVisualRow(nodes: List<NodeSnapshot>): List<List<NodeSnapshot>> {
        val useful = nodes
            .filter { it.text.length <= MAX_CELL_LENGTH && it.height > 0 && it.width > 0 }
            .sortedWith(compareBy<NodeSnapshot> { it.centerY }.thenBy { it.left })
        val groups = mutableListOf<MutableList<NodeSnapshot>>()

        useful.forEach { node ->
            val group = groups.lastOrNull()
            val anchor = group?.map { it.centerY }?.average()
            if (group == null || anchor == null || abs(node.centerY - anchor) > rowTolerancePx) {
                groups += mutableListOf(node)
            } else {
                group += node
            }
        }
        return groups.map { group ->
            group
                .distinctBy { it.text.lowercase() to it.left }
                .sortedBy { it.left }
        }
    }

    private fun parseTableRow(
        row: List<NodeSnapshot>,
        conditionDays: Int,
        sourcePackage: String,
        capturedAt: Long,
        preferredPriceX: Int?,
        quantityX: Int?
    ): QuoteItem? {
        val cells = row.map { it.text }.distinct()
        if (cells.isEmpty() || cells.all(::isHeaderOrChrome)) return null

        val barcodeNode = row.firstOrNull { node ->
            digitsOnly(node.text).length in BARCODE_LENGTHS && node.text.count(Char::isDigit) >= 8
        }
        val priceNodes = row.filter { isPrice(it.text) }
        val priceNode = when {
            preferredPriceX != null -> priceNodes.minByOrNull { abs(it.centerX - preferredPriceX) }
            priceNodes.size == 1 -> priceNodes.single()
            else -> null
        }

        val descriptionNode = row
            .filterNot { it === barcodeNode || it === priceNode }
            .filter { it.text.count(Char::isLetter) >= 3 && !isHeaderOrChrome(it.text) }
            .maxByOrNull { it.text.length }

        val codeNode = row
            .filterNot { it === barcodeNode || it === priceNode || it === descriptionNode }
            .firstOrNull { isProductCode(it.text) }

        val quantityNode = row
            .filterNot { it === barcodeNode || it === codeNode || it in priceNodes }
            .filter { isPlainNumber(it.text) }
            .let { candidates ->
                if (quantityX != null) candidates.minByOrNull { abs(it.centerX - quantityX) }
                else null
            }

        val barcode = barcodeNode?.text?.let(::digitsOnly).orEmpty()
        val price = priceNode?.text?.toBrazilianDecimalOrNull()?.toPlainString().orEmpty()
        val description = descriptionNode?.text.orEmpty()

        val likelyProductRow =
            (barcode.isNotBlank() && price.isNotBlank() && description.isNotBlank()) ||
                (price.isNotBlank() && description.isNotBlank() && cells.size >= 2)
        if (!likelyProductRow) return null

        val productCode = codeNode?.text.orEmpty()
        val quantity = quantityNode?.text?.toBrazilianDecimalOrNull()?.toPlainString().orEmpty()
        val stableSource = listOf(barcode, productCode, description, price, conditionDays.toString()).joinToString("|")

        return QuoteItem(
            id = sha256(stableSource),
            capturedAt = capturedAt,
            conditionDays = conditionDays,
            sourcePackage = sourcePackage,
            productCode = productCode,
            barcode = barcode,
            description = description,
            packaging = "",
            quantity = quantity,
            unitPrice = price,
            rawCells = cells
        )
    }

    private fun distinctProducts(items: List<QuoteItem>): List<QuoteItem> = items.distinctBy { item ->
        item.barcode.ifBlank { "${item.productCode}|${item.description}|${item.unitPrice}" }
    }

    private fun findHeaderX(nodes: List<NodeSnapshot>, pattern: Regex): Int? = nodes
        .filter { pattern.containsMatchIn(it.text) }
        .minByOrNull { it.top }
        ?.centerX

    private fun conditionDaysHeaderRegex(days: Int) = Regex(
        "^\\s*$days\\s*(?:d|dia|dias)\\s*$",
        RegexOption.IGNORE_CASE
    )

    private fun isPrice(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains("R$", ignoreCase = true)) return trimmed.toBrazilianDecimalOrNull() != null
        return PRICE.matches(trimmed) && trimmed.toBrazilianDecimalOrNull() != null
    }

    private fun isPlainNumber(text: String): Boolean = PLAIN_NUMBER.matches(text.trim())

    private fun isProductCode(text: String): Boolean {
        val compact = text.trim().replace(" ", "")
        if (compact.length !in 2..24 || compact.any { !it.isLetterOrDigit() && it !in "-./" }) return false
        return compact.any(Char::isDigit) && digitsOnly(compact).length !in BARCODE_LENGTHS
    }

    private fun isHeaderOrChrome(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return CHROME_WORDS.any { normalized == it || normalized.startsWith("$it:") }
    }

    private fun extractFirst(text: String, pattern: Regex): String? = pattern.find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun anchorSpecificity(node: NodeSnapshot): Int = node.text.length + node.height.coerceAtLeast(0)

    private fun firstCardLookBehindPx(): Int = maxOf(rowTolerancePx * 12, 160)

    private fun duplicateAnchorTolerancePx(): Int = maxOf(rowTolerancePx * 10, 180)

    private fun digitsOnly(value: String): String = value.filter(Char::isDigit)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private data class CardAnchor(val node: NodeSnapshot, val productCode: String)

    companion object {
        private val BARCODE_LENGTHS = setOf(8, 12, 13, 14)
        private val PRICE = Regex("(?:\\d{1,3}(?:\\.\\d{3})*|\\d+)[,.]\\d{2,4}")
        private val PLAIN_NUMBER = Regex("\\d+(?:[,.]\\d{1,3})?")
        private val QUANTITY_HEADER = Regex("\\b(qtd|qtde|quantidade)\\b", RegexOption.IGNORE_CASE)
        private val PRODUCT_CODE_FIELD = Regex(
            "(?:^|\\s)c[oó]d(?:igo)?\\.?\\s*:\\s*([A-Z0-9][A-Z0-9./-]{1,23})",
            RegexOption.IGNORE_CASE
        )
        private val PRODUCT_CODE_LABEL = Regex(
            "c[oó]d(?:igo)?\\.?\\s*:",
            RegexOption.IGNORE_CASE
        )
        private val PACKAGING_FIELD = Regex(
            "(?:^|\\s)emb(?:alagem)?\\.?\\s*:\\s*([A-Z]{1,5}\\s*[-/]?\\s*\\d+(?:[,.]\\d+)?)",
            RegexOption.IGNORE_CASE
        )
        private val UNIT_PRICE_FIELD = Regex(
            "(?:^|\\s)un\\.?\\s*:\\s*(?:R\\$\\s*)?((?:\\d{1,3}(?:\\.\\d{3})*|\\d+)(?:[,.]\\d{1,4})?)",
            RegexOption.IGNORE_CASE
        )
        private val EAN_FIELD = Regex(
            "(?:^|\\s)EAN\\s*:\\s*([0-9][0-9 .-]{7,20})",
            RegexOption.IGNORE_CASE
        )
        private val PACK_QUANTITY = Regex("(\\d+(?:[,.]\\d+)?)\\s*$")
        private val BLINK_FIELD_LABEL = Regex(
            "(?:^|\\s)(?:c[oó]d(?:igo)?|emb(?:alagem)?|un|EAN|estoque|prazo\\s+de\\s+validade)\\.?\\s*:",
            RegexOption.IGNORE_CASE
        )
        private val PRICE_ANYWHERE = Regex("R\\$\\s*\\d", RegexOption.IGNORE_CASE)
        private val CHROME_WORDS = setOf(
            "produto", "produtos", "código", "codigo", "cód. barras", "código de barras",
            "descrição", "descricao", "quantidade", "qtd", "qtde", "preço", "preco",
            "valor", "total", "voltar", "avançar", "salvar", "cancelar", "pesquisar",
            "todos", "mix clientes", "legenda", "config", "famílias", "familias", "núm", "num",
            "blink mobile", "prazo de validade", "estoque", "ped", "desc"
        )
        private const val MAX_CELL_LENGTH = 240
        private const val MAX_CARD_CELL_LENGTH = 500
        private const val MAX_DESCRIPTION_LENGTH = 240
    }
}

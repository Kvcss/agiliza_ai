package com.agilizaai.cotacao.capture

import com.agilizaai.cotacao.data.QuoteItem
import com.agilizaai.cotacao.data.toBrazilianDecimalOrNull
import java.security.MessageDigest
import kotlin.math.abs

/** Reads the actual views exposed by ION Vendas 1.26.x. */
object IonProductReader {
    private const val ID_CODE = "textViewCodigoProduto"
    private const val ID_DESCRIPTION = "textViewDescricao"
    private const val ID_BARCODE = "textViewCodigoBarras"
    private const val ID_UNIT = "textViewUnidade"
    private const val ID_PRICE = "textViewPrecoTabela"
    private const val ID_PACKAGING = "textViewEmbalagem"
    private const val ID_STOCK = "textViewEstoque"

    private const val ID_DETAIL_DESCRIPTION = "textViewProduto"
    private const val ID_DETAIL_PRICE = "textViewProdutoPrecoTabela"
    private const val ID_DETAIL_CASE = "textViewQtUnitCX"
    private const val ID_DETAIL_UNIT = "textViewProdutoUN"
    private const val ID_DETAIL_STOCK = "textViewProdutoEstoque"

    // The product list in ION Vendas 1.26.7 uses a reusable/custom row layout
    // instead of the descriptive IDs used by some other 1.26.x builds.
    private const val ID_GENERIC_TOP = "textViewRowCustom1"
    private const val ID_GENERIC_LABEL = "textViewRowCustom"
    private const val ID_GENERIC_VALUE = "textViewRowCustom3"
    private val GENERIC_PRODUCT_CODE = Regex("^[0-9][0-9.\\-]*$")

    data class ListProduct(
        val productCode: String,
        val description: String,
        val barcode: String,
        val barcodeTruncated: Boolean = false,
        val unit: String,
        val packaging: String,
        val unitPrice: String,
        val stock: String,
        val centerY: Int,
        val rawCells: List<String>
    ) {
        val stableKey: String
            get() = variantIdentity(
                productCode = productCode,
                barcode = barcode,
                barcodeTruncated = barcodeTruncated,
                description = description,
                packaging = packaging,
                unitsPerCase = parseUnitsPerCase(packaging)
            ).stableKey
    }

    /**
     * Identity of one sellable ION variant. Product code alone is not unique: ION can expose
     * the same code more than once for different Cód. Emb./Emb./Cx Unit values.
     */
    internal data class VariantIdentity(
        val productCode: String,
        val barcode: String,
        val barcodeTruncated: Boolean,
        val description: String,
        val packaging: String,
        val unitsPerCase: String
    ) {
        val stableKey: String
            get() = listOf(
                "variant-v2",
                "code=$productCode",
                "barcode=${if (barcodeTruncated) "prefix:" else "full:"}$barcode",
                "pack=$packaging",
                "units=$unitsPerCase",
                "name=$description"
            ).joinToString("|")

        /** Missing/truncated fields are compatible only as a fallback; the service merges such
         * a candidate only when there is exactly one compatible captured variant. */
        fun isCompatibleWith(other: VariantIdentity): Boolean {
            if (productCode.isNotBlank() && other.productCode.isNotBlank() && productCode != other.productCode) {
                return false
            }
            if (!barcodeCompatible(other)) return false
            if (!valueCompatible(packaging, other.packaging)) return false
            if (!valueCompatible(unitsPerCase, other.unitsPerCase)) return false
            if (!descriptionCompatible(description, other.description)) return false

            return (productCode.isNotBlank() && productCode == other.productCode) ||
                (barcode.isNotBlank() && other.barcode.isNotBlank() && barcodeCompatible(other)) ||
                (productCode.isBlank() && other.productCode.isBlank() &&
                    barcode.isBlank() && other.barcode.isBlank() &&
                    description.isNotBlank() && descriptionCompatible(description, other.description))
        }

        fun mergedWith(other: VariantIdentity): VariantIdentity {
            val useOtherBarcode = barcode.isBlank() ||
                (barcodeTruncated && !other.barcodeTruncated && other.barcode.startsWith(barcode))
            return copy(
                productCode = productCode.ifBlank { other.productCode },
                barcode = if (useOtherBarcode) other.barcode else barcode,
                barcodeTruncated = if (useOtherBarcode) other.barcodeTruncated else barcodeTruncated,
                description = if (other.description.length > description.length) other.description else description,
                packaging = packaging.ifBlank { other.packaging },
                unitsPerCase = unitsPerCase.ifBlank { other.unitsPerCase }
            )
        }

        private fun barcodeCompatible(other: VariantIdentity): Boolean {
            if (barcode.isBlank() || other.barcode.isBlank()) return true
            if (barcode == other.barcode) return true
            return (barcodeTruncated && other.barcode.startsWith(barcode)) ||
                (other.barcodeTruncated && barcode.startsWith(other.barcode))
        }

        private fun valueCompatible(first: String, second: String): Boolean =
            first.isBlank() || second.isBlank() || first == second

        private fun descriptionCompatible(first: String, second: String): Boolean {
            if (first.isBlank() || second.isBlank() || first == second) return true
            val shorter = if (first.length <= second.length) first else second
            val longer = if (first.length > second.length) first else second
            return shorter.length >= MIN_DESCRIPTION_PREFIX && longer.startsWith(shorter)
        }
    }

    fun readList(nodes: List<NodeSnapshot>): List<ListProduct> {
        val codes = nodes.filter { it.hasId(ID_CODE) && it.text.isNotBlank() }.sortedBy { it.centerY }
        if (codes.isEmpty()) return readGenericList(nodes)
        return codes.mapIndexedNotNull { index, codeNode ->
            val previous = codes.getOrNull(index - 1)
            val next = codes.getOrNull(index + 1)
            val top = previous?.let { (it.centerY + codeNode.centerY) / 2 } ?: codeNode.top - 100
            val bottom = next?.let { (it.centerY + codeNode.centerY) / 2 } ?: codeNode.bottom + 220
            val card = nodes.filter { it.centerY in top until bottom }
            val description = card.value(ID_DESCRIPTION)
            val price = card.value(ID_PRICE).toBrazilianDecimalOrNull()?.toPlainString().orEmpty()
            val rawBarcode = card.value(ID_BARCODE)
            if (description.isBlank() || price.isBlank()) return@mapIndexedNotNull null
            ListProduct(
                productCode = codeNode.text.trim(),
                description = description,
                barcode = rawBarcode.filter(Char::isDigit),
                barcodeTruncated = isTruncated(rawBarcode),
                unit = card.value(ID_UNIT),
                packaging = card.value(ID_PACKAGING),
                unitPrice = price,
                stock = card.value(ID_STOCK),
                centerY = codeNode.centerY,
                rawCells = card.map { it.text }.filter { it.isNotBlank() }.distinct()
            )
        }.distinctBy { it.stableKey }
    }

    private fun readGenericList(nodes: List<NodeSnapshot>): List<ListProduct> {
        val codes = nodes
            .filter { it.hasId(ID_GENERIC_TOP) && GENERIC_PRODUCT_CODE.matches(it.text.trim()) }
            .sortedBy { it.centerY }
        if (codes.isEmpty()) return emptyList()

        return codes.mapIndexedNotNull { index, codeNode ->
            val next = codes.getOrNull(index + 1)
            // The code is close to the top of each tall custom card. A midpoint
            // would cut off its Emb./Preço rows, so start/end relative to the
            // next product code instead.
            val top = codeNode.centerY - 100
            val bottom = next?.let { it.centerY - 100 } ?: codeNode.centerY + 400
            val card = nodes.filter { it.centerY in top until bottom }
            val description = card.asSequence()
                .filter { it.hasId(ID_GENERIC_TOP) && it !== codeNode }
                .filterNot { GENERIC_PRODUCT_CODE.matches(it.text.trim()) }
                .minByOrNull { abs(it.centerY - codeNode.centerY) }
                ?.text
                ?.trim()
                .orEmpty()
            val price = card.genericValueAfterLabel("PRECO")
                .toBrazilianDecimalOrNull()
                ?.toPlainString()
                .orEmpty()
            val rawBarcode = card.genericValueAfterLabel("COD EMB")
            if (description.isBlank() || price.isBlank()) return@mapIndexedNotNull null

            ListProduct(
                productCode = codeNode.text.trim(),
                description = description,
                barcode = rawBarcode.filter(Char::isDigit),
                barcodeTruncated = isTruncated(rawBarcode),
                unit = card.genericValueAfterLabel("UND"),
                packaging = card.genericValueAfterLabel("EMB"),
                unitPrice = price,
                stock = card.genericValueAfterLabel("EST"),
                centerY = codeNode.centerY,
                rawCells = card.map { it.text }.filter { it.isNotBlank() }.distinct()
            )
        }.distinctBy { it.stableKey }
    }

    fun readDetail(
        nodes: List<NodeSnapshot>,
        listProduct: ListProduct,
        conditionDays: Int,
        sourcePackage: String,
        capturedAt: Long = System.currentTimeMillis()
    ): QuoteItem? {
        val casePrice = nodes.value(ID_DETAIL_PRICE)
            .ifBlank { nodes.value(ID_PRICE) }
            .toBrazilianDecimalOrNull()
            ?.toPlainString()
            .orEmpty()
        if (casePrice.isBlank()) return null

        val code = nodes.value(ID_CODE).ifBlank { listProduct.productCode }
        val description = nodes.value(ID_DETAIL_DESCRIPTION)
            .ifBlank { nodes.value(ID_DESCRIPTION) }
            .ifBlank { listProduct.description }
        if (code.isBlank() || description.isBlank()) return null

        val caseText = nodes.value(ID_DETAIL_CASE)
        val unitsPerCase = parseUnitsPerCase(caseText)
            .ifBlank { parseUnitsPerCase(listProduct.packaging) }
        val packaging = nodes.value(ID_PACKAGING).ifBlank { listProduct.packaging }
        val barcode = listProduct.barcode
        val salesUnit = nodes.value(ID_DETAIL_UNIT).ifBlank { listProduct.unit }
        // Pço Tabela/Valor is the authoritative sale-package price. Dividing it by the
        // confirmed case size keeps Valor Un. consistent with the selected payment term.
        // The list's Preço is used only when ION does not expose a valid case size.
        val derivedUnitPrice = casePrice.toBrazilianDecimalOrNull()?.let { total ->
            unitsPerCase.toBrazilianDecimalOrNull()
                ?.takeIf { it > java.math.BigDecimal.ZERO }
                ?.let { units -> total.divide(units, 6, java.math.RoundingMode.HALF_UP) }
        }
        val normalizedUnitPrice = derivedUnitPrice?.stripTrailingZeros()?.toPlainString()
            ?: listProduct.unitPrice.toBrazilianDecimalOrNull()?.toPlainString().orEmpty()
        if (normalizedUnitPrice.isBlank()) return null
        // The detail value is authoritative. The list value is only a fallback for ION
        // builds that do not expose textViewProdutoEstoque in the accessibility tree.
        val rawStock = nodes.value(ID_DETAIL_STOCK).ifBlank { listProduct.stock }
        val stock = rawStock.toBrazilianDecimalOrNull()
            ?.stripTrailingZeros()
            ?.toPlainString()
            .orEmpty()
        val raw = (listProduct.rawCells + nodes.map { it.text }).filter { it.isNotBlank() }.distinct()
        val identity = variantIdentity(
            productCode = code,
            barcode = barcode,
            barcodeTruncated = listProduct.barcodeTruncated,
            description = description,
            packaging = packaging,
            unitsPerCase = unitsPerCase
        )
        val id = sha256(identity.stableKey)
        return QuoteItem(
            id = id,
            capturedAt = capturedAt,
            conditionDays = conditionDays,
            sourcePackage = sourcePackage,
            productCode = code,
            barcode = barcode,
            description = description,
            packaging = packaging,
            quantity = unitsPerCase,
            unitPrice = normalizedUnitPrice,
            rawCells = raw + listOfNotNull(
                nodes.value(ID_DETAIL_UNIT).takeIf { it.isNotBlank() }?.let { "Unidade: $it" },
                nodes.value(ID_DETAIL_STOCK).takeIf { it.isNotBlank() }?.let { "Estoque: $it" },
                caseText.takeIf { it.isNotBlank() }?.let { "Cx Unit/Tot: $it" }
            ),
            pricesByDays = mapOf(conditionDays to normalizedUnitPrice),
            unitsPerCase = unitsPerCase,
            stock = stock,
            casePricesByDays = mapOf(conditionDays to casePrice),
            salesUnit = salesUnit
        )
    }

    fun isProductList(nodes: List<NodeSnapshot>): Boolean =
        nodes.none { it.hasId(ID_DETAIL_PRICE) } && readList(nodes).isNotEmpty()

    fun isProductDetail(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { it.hasId(ID_DETAIL_PRICE) } || nodes.any { it.hasId(ID_DETAIL_CASE) }

    internal fun variantIdentity(listProduct: ListProduct, item: QuoteItem): VariantIdentity =
        variantIdentity(
            productCode = item.productCode.ifBlank { listProduct.productCode },
            barcode = item.barcode.ifBlank { listProduct.barcode },
            barcodeTruncated = listProduct.barcodeTruncated,
            description = item.description.ifBlank { listProduct.description },
            packaging = item.packaging.ifBlank { listProduct.packaging },
            unitsPerCase = item.unitsPerCase.ifBlank { parseUnitsPerCase(listProduct.packaging) }
        )

    internal fun variantIdentity(
        productCode: String,
        barcode: String,
        barcodeTruncated: Boolean,
        description: String,
        packaging: String,
        unitsPerCase: String
    ): VariantIdentity = VariantIdentity(
        productCode = normalizeIdentifier(productCode),
        barcode = barcode.filter(Char::isDigit),
        barcodeTruncated = barcodeTruncated,
        description = normalizeName(description),
        packaging = normalizeVariantValue(packaging),
        unitsPerCase = normalizeVariantValue(unitsPerCase)
    )

    private fun List<NodeSnapshot>.value(idSuffix: String): String = asSequence()
        .filter { it.hasId(idSuffix) }
        .map { it.text.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    private fun List<NodeSnapshot>.genericValueAfterLabel(label: String): String {
        val labelNode = firstOrNull {
            it.hasId(ID_GENERIC_LABEL) && normalizeName(it.text).startsWith(label)
        } ?: return ""
        return asSequence()
            .filter { it.hasId(ID_GENERIC_VALUE) }
            .filter { abs(it.centerY - labelNode.centerY) <= 12 }
            .filter { it.left >= labelNode.right - 8 }
            .minByOrNull { it.left }
            ?.text
            ?.trim()
            .orEmpty()
    }

    private fun NodeSnapshot.hasId(suffix: String): Boolean =
        viewId == suffix || viewId.endsWith(":id/$suffix") || viewId.endsWith("/$suffix")

    private fun parseUnitsPerCase(value: String): String {
        val first = value.substringBefore('/').trim()
        val explicit = Regex("(?:CX|CAIXA|EMB)[^0-9]*([0-9]+(?:[,.][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        return (explicit ?: first)
            .toBrazilianDecimalOrNull()
            ?.stripTrailingZeros()
            ?.toPlainString()
            .orEmpty()
    }

    private fun normalizeIdentifier(value: String): String = normalizeName(value).replace(" ", "")

    private fun normalizeVariantValue(value: String): String = value.toBrazilianDecimalOrNull()
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: normalizeName(value)

    private fun isTruncated(value: String): Boolean =
        value.contains("...") || value.contains('\u2026')

    internal fun normalizeName(value: String): String = java.text.Normalizer
        .normalize(value.uppercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private const val MIN_DESCRIPTION_PREFIX = 12
}

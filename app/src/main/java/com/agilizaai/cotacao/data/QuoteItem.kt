package com.agilizaai.cotacao.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

data class QuoteItem(
    val id: String,
    val capturedAt: Long,
    val conditionDays: Int,
    val sourcePackage: String,
    val productCode: String,
    val barcode: String,
    val description: String,
    val packaging: String = "",
    val quantity: String,
    val unitPrice: String,
    val rawCells: List<String>,
    /** Preços unitários efetivamente lidos no ION, separados por prazo. */
    val pricesByDays: Map<Int, String> = mapOf(conditionDays to unitPrice),
    /** Quantidade de unidades que compõe uma caixa. */
    val unitsPerCase: String = quantity,
    /** Estoque real exposto pelo ION para esta variante. */
    val stock: String = "",
    /** Preço total da embalagem/caixa mostrado como Pço Tabela/Valor no detalhe. */
    val casePricesByDays: Map<Int, String> = emptyMap(),
    /** Unidade comercial da variante no ION, por exemplo CJ ou CX. */
    val salesUnit: String = ""
) {
    fun displayPrice(days: Int = conditionDays): String {
        val raw = priceForDays(days)
        val number = raw.toBigDecimalOrNull() ?: return raw
        return NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(number)
    }

    /** Only a value read for this exact ION payment term is authoritative. */
    fun priceForDays(days: Int): String = pricesByDays[days].orEmpty()

    fun priceForWebsite(days: Int = conditionDays): String = priceForDays(days).toBigDecimalOrNull()
        ?.setScale(2, java.math.RoundingMode.HALF_UP)
        ?.toPlainString()
        ?: priceForDays(days)

    fun casePriceForDays(days: Int): BigDecimal? {
        casePricesByDays[days]?.toBrazilianDecimalOrNull()?.let { return it }
        val price = priceForDays(days).toBigDecimalOrNull() ?: return null
        val count = unitsPerCase.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: quantity.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            ?: BigDecimal.ONE
        return price.multiply(count).setScale(3, java.math.RoundingMode.HALF_UP)
    }

    fun quantityForDisplay(): String = quantity.toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: quantity

    fun stockValueOrNull(): BigDecimal? = stock.toBrazilianDecimalOrNull()

    /**
     * A base aceita somente estoque confirmado de pelo menos três unidades.
     * Valor ausente ou ilegível é rejeitado de forma conservadora.
     */
    fun hasMinimumStock(): Boolean = stockValueOrNull()?.let { it >= MINIMUM_STOCK } == true

    companion object {
        val MINIMUM_STOCK: BigDecimal = BigDecimal("3")
    }
}

internal fun String.toBrazilianDecimalOrNull(): BigDecimal? {
    val cleaned = trim()
        .replace("R$", "", ignoreCase = true)
        .replace("\u00A0", "")
        .replace(" ", "")
    if (cleaned.isBlank()) return null

    val normalized = when {
        cleaned.contains(',') && cleaned.contains('.') -> cleaned.replace(".", "").replace(',', '.')
        cleaned.contains(',') -> cleaned.replace(',', '.')
        else -> cleaned
    }
    return normalized.toBigDecimalOrNull()
}

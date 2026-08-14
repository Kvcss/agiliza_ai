package com.agilizaai.cotacao.web

import com.agilizaai.cotacao.data.QuoteItem

/** Builds a portal-safe barcode index without silently overwriting conflicting prices. */
object PortalPriceIndex {
    data class Entry(val barcode: String, val price: String)

    data class Result(
        val entries: List<Entry>,
        val conflictingBarcodes: Set<String>
    )

    fun build(items: List<QuoteItem>, conditionDays: Int): Result {
        val candidates = items.mapNotNull { item ->
            val barcode = item.barcode.filter(Char::isDigit)
            val rawPrice = item.priceForDays(conditionDays).trim()
            if (barcode.isBlank() || rawPrice.isBlank()) return@mapNotNull null
            Candidate(
                barcode = barcode,
                canonicalPrice = canonicalPrice(rawPrice),
                websitePrice = item.priceForWebsite(conditionDays)
            )
        }

        val entries = mutableListOf<Entry>()
        val conflicts = linkedSetOf<String>()
        candidates.groupBy { it.barcode }.forEach { (barcode, variants) ->
            val prices = variants.map { it.canonicalPrice }.distinct()
            if (prices.size == 1) {
                entries += Entry(barcode, variants.first().websitePrice)
            } else {
                conflicts += barcode
            }
        }
        return Result(entries, conflicts)
    }

    private fun canonicalPrice(value: String): String = value.toBigDecimalOrNull()
        ?.stripTrailingZeros()
        ?.toPlainString()
        ?: value

    private data class Candidate(
        val barcode: String,
        val canonicalPrice: String,
        val websitePrice: String
    )
}

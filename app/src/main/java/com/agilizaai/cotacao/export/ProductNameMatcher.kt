package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import java.math.BigDecimal
import java.text.Normalizer

/** Conservative name matching: uncertain products are intentionally left blank. */
object ProductNameMatcher {
    data class Match(val item: QuoteItem, val score: Double)

    fun find(productName: String, items: List<QuoteItem>, expectedUnitsPerCase: String? = null): Match? {
        val target = normalize(productName)
        if (target.length < 4) return null
        val expectedUnits = expectedUnitsPerCase?.let(::parseUnits)
        val candidates = if (expectedUnits != null) {
            items.filter { itemUnits(it)?.compareTo(expectedUnits) == 0 }
        } else {
            items
        }
        // A valid case quantity in the quotation is authoritative. Falling back
        // to another package would silently quote the wrong case.
        if (expectedUnits != null && candidates.isEmpty()) return null

        val exact = candidates.filter { normalize(it.description) == target }
        if (exact.size == 1) return Match(exact.single(), 1.0)
        // A name alone cannot distinguish case/packaging variants. Choosing the
        // first capture would silently fill the quotation with the wrong case price.
        if (exact.size > 1) return null

        val ranked = candidates.mapNotNull { item ->
            val candidate = normalize(item.description)
            val score = similarity(target, candidate)
            if (score > 0.0) Match(item, score) else null
        }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (best.score < MIN_SCORE) return null
        if (second != null && best.score - second.score < MIN_MARGIN) return null
        return best
    }

    internal fun normalize(value: String): String = Normalizer
        .normalize(value.uppercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    internal fun similarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val leftNumbers = tokens(left).filter { it.any(Char::isDigit) }.toSet()
        val rightNumbers = tokens(right).filter { it.any(Char::isDigit) }.toSet()
        if (leftNumbers.isNotEmpty() && rightNumbers.isNotEmpty() && leftNumbers != rightNumbers) return 0.0

        val leftTokens = tokens(left).filterNot { it in STOP_WORDS }.toSet()
        val rightTokens = tokens(right).filterNot { it in STOP_WORDS }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val intersection = leftTokens.intersect(rightTokens).size.toDouble()
        val union = leftTokens.union(rightTokens).size.toDouble()
        val jaccard = intersection / union
        val containment = intersection / minOf(leftTokens.size, rightTokens.size).toDouble()
        val compactLeft = left.replace(" ", "")
        val compactRight = right.replace(" ", "")
        val containsBonus = if (
            minOf(compactLeft.length, compactRight.length) >= 12 &&
            (compactLeft.contains(compactRight) || compactRight.contains(compactLeft))
        ) 0.12 else 0.0
        return (jaccard * 0.58 + containment * 0.42 + containsBonus).coerceAtMost(0.99)
    }

    internal fun parseUnits(value: String): BigDecimal? {
        val numeric = Regex("[0-9]+(?:[.,][0-9]+)?").find(value.trim())?.value ?: return null
        return numeric.replace(',', '.').toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.takeIf { it > BigDecimal.ZERO }
    }

    private fun itemUnits(item: QuoteItem): BigDecimal? =
        parseUnits(item.unitsPerCase) ?: parseUnits(item.quantity)

    private fun tokens(value: String): List<String> = value.split(' ').filter { it.length >= 2 }

    private val STOP_WORDS = setOf("DE", "DA", "DO", "DAS", "DOS", "COM", "SEM", "UN", "UND", "UNID")
    private const val MIN_SCORE = 0.76
    private const val MIN_MARGIN = 0.08
}

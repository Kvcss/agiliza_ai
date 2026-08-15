package com.agilizaai.cotacao.export

import com.agilizaai.cotacao.data.QuoteItem
import java.math.BigDecimal
import java.text.Normalizer

/** Conservative name matching: uncertain products are intentionally left blank. */
object ProductNameMatcher {
    data class Match(val item: QuoteItem, val score: Double)

    enum class VariantPolicy {
        /** Existing behavior: the quotation's case quantity is authoritative. */
        MATCH_EXPECTED_UNITS,

        /** Excel-by-box behavior: prefer one compatible ION variant sold as CJ. */
        PREFER_SINGLE_CJ
    }

    private enum class MeasureKind { WEIGHT_G, VOLUME_ML, VOLTAGE_V, POWER_W, LENGTH_MM, COUNT }

    private data class UnitSpec(val kind: MeasureKind, val multiplier: BigDecimal)

    private data class ProductFeatures(
        val words: Set<String>,
        val measures: Map<MeasureKind, Set<String>>,
        val numbers: Set<String>,
        val packageCounts: Set<String>
    )

    private data class IdentityCandidate(val match: Match, val exact: Boolean)

    fun find(
        productName: String,
        items: List<QuoteItem>,
        expectedUnitsPerCase: String? = null,
        variantPolicy: VariantPolicy = VariantPolicy.MATCH_EXPECTED_UNITS
    ): Match? {
        val targetText = normalize(productName)
        if (targetText.length < 4) return null
        val target = features(productName)
        if (target.words.isEmpty()) return null

        if (variantPolicy == VariantPolicy.PREFER_SINGLE_CJ) {
            return findPreferredCj(targetText, target, items)
        }

        val expectedUnits = expectedUnitsPerCase?.let(::parseUnits)
        val candidates = if (expectedUnits != null) {
            items.filter { itemUnits(it)?.compareTo(expectedUnits) == 0 }
        } else {
            items
        }
        // A valid case quantity in the quotation is authoritative. Falling back
        // to another package would silently quote the wrong case.
        if (expectedUnits != null && candidates.isEmpty()) return null

        val exact = candidates.filter { normalize(it.description) == targetText }
        if (exact.size == 1) return Match(exact.single(), 1.0)
        // A name alone cannot distinguish case/packaging variants. Choosing the
        // first capture would silently fill the quotation with the wrong case price.
        if (exact.size > 1) return null

        val ranked = candidates.mapNotNull { item ->
            val score = featureSimilarity(target, features(item.description))
            if (score > 0.0) Match(item, score) else null
        }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)
        if (best.score < MIN_SCORE) return null
        if (second != null && best.score - second.score < MIN_MARGIN) return null

        // Without a case quantity supplied by the quotation, two otherwise equal
        // products in different captured cases are not safely distinguishable.
        if (expectedUnits == null && target.packageCounts.isEmpty()) {
            val bestUnits = itemUnits(best.item)
            if (bestUnits != null) {
                val packagingAlternative = candidates.any { other ->
                    other !== best.item &&
                        itemUnits(other)?.let { it.compareTo(bestUnits) != 0 } == true &&
                        sameProductIgnoringCase(best.item.description, other.description)
                }
                if (packagingAlternative) return null
            }
        }
        return best
    }

    /**
     * The Vila Nova quotation's "cx c/" field describes the buyer's requested
     * quantity and cannot be used to select the ION packaging variant. For this
     * mode only, CJ is an explicit business preference after product identity is
     * resolved. Within that identity, two CJ rows are left blank instead of guessing.
     */
    private fun findPreferredCj(
        targetText: String,
        target: ProductFeatures,
        items: List<QuoteItem>
    ): Match? {
        val compatible = items.mapNotNull { item ->
            val exact = normalize(item.description) == targetText
            val score = if (exact) {
                1.0
            } else {
                featureSimilarity(target, features(item.description))
            }
            score.takeIf { it >= MIN_SCORE }?.let { IdentityCandidate(Match(item, it), exact) }
        }
        val selectedIdentity = selectIdentityGroup(compatible) ?: return null
        val cj = selectedIdentity.filter { normalize(it.match.item.salesUnit) == SALES_UNIT_CJ }
        return when {
            cj.size == 1 -> cj.single().match
            cj.size > 1 -> null
            selectedIdentity.size == 1 -> selectedIdentity.single().match
            else -> null
        }
    }

    /**
     * Resolve product identity before considering the commercial unit. An exact
     * description wins first. Fuzzy identities must have a clear margin over the
     * next distinct product, while case variants of the same product stay grouped.
     */
    private fun selectIdentityGroup(
        candidates: List<IdentityCandidate>
    ): List<IdentityCandidate>? {
        if (candidates.isEmpty()) return null
        val groups = mutableListOf<MutableList<IdentityCandidate>>()
        candidates.sortedByDescending { it.match.score }.forEach { candidate ->
            val equivalentGroups = groups.filter { group ->
                group.all { existing ->
                    sameProductIgnoringCase(
                        candidate.match.item.description,
                        existing.match.item.description
                    )
                }
            }
            // A candidate that bridges two otherwise distinct groups is itself
            // ambiguous; keeping it separate prevents transitive fuzzy grouping.
            if (equivalentGroups.size == 1) {
                equivalentGroups.single() += candidate
            } else {
                groups += mutableListOf(candidate)
            }
        }

        val exactGroups = groups.filter { group -> group.any(IdentityCandidate::exact) }
        if (exactGroups.size == 1) return exactGroups.single()
        if (exactGroups.size > 1) return null

        val rankedGroups = groups.sortedByDescending { group -> group.maxOf { it.match.score } }
        val best = rankedGroups.firstOrNull() ?: return null
        val bestScore = best.maxOf { it.match.score }
        val secondScore = rankedGroups.getOrNull(1)?.maxOf { it.match.score }
        if (bestScore < MIN_SCORE) return null
        if (secondScore != null && bestScore - secondScore < MIN_MARGIN) return null
        return best
    }

    /**
     * General text normalization is also used by the workbook reader for headers,
     * so product-specific abbreviation expansion deliberately lives in [features].
     */
    internal fun normalize(value: String): String = ascii(value)
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    internal fun similarity(left: String, right: String): Double =
        featureSimilarity(features(left), features(right))

    internal fun parseUnits(value: String): BigDecimal? {
        val numeric = Regex("[0-9]+(?:[.,][0-9]+)?").find(value.trim())?.value ?: return null
        return numeric.replace(',', '.').toBigDecimalOrNull()
            ?.stripTrailingZeros()
            ?.takeIf { it > BigDecimal.ZERO }
    }

    private fun itemUnits(item: QuoteItem): BigDecimal? =
        parseUnits(item.unitsPerCase) ?: parseUnits(item.quantity)

    private fun sameProductIgnoringCase(left: String, right: String): Boolean {
        val leftFeatures = features(left).copy(packageCounts = emptySet())
        val rightFeatures = features(right).copy(packageCounts = emptySet())
        return featureSimilarity(leftFeatures, rightFeatures) >= SAME_PRODUCT_SCORE
    }

    private fun featureSimilarity(left: ProductFeatures, right: ProductFeatures): Double {
        if (left.words.isEmpty() || right.words.isEmpty()) return 0.0
        if (hasConflictingVariants(left, right)) return 0.0

        val leftWordWeight = left.words.sumOf(::wordWeight)
        val rightWordWeight = right.words.sumOf(::wordWeight)
        val wordIntersection = softWordIntersection(left.words, right.words)

        val leftSignatures = signatures(left)
        val rightSignatures = signatures(right)
        val signatureIntersection = leftSignatures.keys.intersect(rightSignatures.keys)
            .sumOf { minOf(leftSignatures.getValue(it), rightSignatures.getValue(it)) }
        val leftWeight = leftWordWeight + leftSignatures.values.sum()
        val rightWeight = rightWordWeight + rightSignatures.values.sum()
        val intersection = wordIntersection + signatureIntersection
        if (intersection <= 0.0) return 0.0

        // If both descriptions contain distinctive words, at least one of them
        // must agree. This keeps a shared generic category and size from joining
        // different brands or variants.
        val leftDistinctive = left.words.filterNot { it in GENERIC_WORDS }.toSet()
        val rightDistinctive = right.words.filterNot { it in GENERIC_WORDS }.toSet()
        if (leftDistinctive.isNotEmpty() && rightDistinctive.isNotEmpty()) {
            val distinctiveAgreement = leftDistinctive.maxOf { a ->
                rightDistinctive.maxOf { b -> tokenSimilarity(a, b) }
            }
            if (distinctiveAgreement < MIN_TOKEN_SIMILARITY) return 0.0
        }

        val union = leftWeight + rightWeight - intersection
        if (union <= 0.0) return 0.0
        val jaccard = intersection / union
        val containment = intersection / minOf(leftWeight, rightWeight)
        return (jaccard * 0.58 + containment * 0.42).coerceIn(0.0, 0.99)
    }

    private fun hasConflictingVariants(left: ProductFeatures, right: ProductFeatures): Boolean {
        MeasureKind.entries.forEach { kind ->
            val leftValues = left.measures[kind].orEmpty()
            val rightValues = right.measures[kind].orEmpty()
            if (leftValues.isNotEmpty() && rightValues.isNotEmpty() && leftValues != rightValues) return true
        }
        if (left.numbers.isNotEmpty() && right.numbers.isNotEmpty() && left.numbers != right.numbers) return true
        if (
            left.packageCounts.isNotEmpty() &&
            right.packageCounts.isNotEmpty() &&
            left.packageCounts != right.packageCounts
        ) return true
        return false
    }

    private fun signatures(features: ProductFeatures): Map<String, Double> = buildMap {
        features.measures.forEach { (kind, values) ->
            values.forEach { value -> put("M:$kind:$value", MEASURE_WEIGHT) }
        }
        features.numbers.forEach { value -> put("N:$value", NUMBER_WEIGHT) }
        features.packageCounts.forEach { value -> put("P:$value", PACKAGE_WEIGHT) }
    }

    private fun softWordIntersection(left: Set<String>, right: Set<String>): Double {
        data class PairScore(val left: String, val right: String, val similarity: Double)

        val pairs = left.flatMap { a ->
            right.mapNotNull { b ->
                val similarity = tokenSimilarity(a, b)
                if (similarity >= MIN_TOKEN_SIMILARITY) PairScore(a, b, similarity) else null
            }
        }.sortedByDescending { it.similarity }
        val usedLeft = mutableSetOf<String>()
        val usedRight = mutableSetOf<String>()
        var intersection = 0.0
        pairs.forEach { pair ->
            if (pair.left !in usedLeft && pair.right !in usedRight) {
                usedLeft += pair.left
                usedRight += pair.right
                intersection += minOf(wordWeight(pair.left), wordWeight(pair.right)) * pair.similarity
            }
        }
        return intersection
    }

    private fun tokenSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val shorter = if (left.length <= right.length) left else right
        val longer = if (left.length > right.length) left else right
        if (
            shorter.length >= MIN_PREFIX_LENGTH &&
            longer.startsWith(shorter) &&
            shorter.length.toDouble() / longer.length >= MIN_PREFIX_RATIO
        ) return PREFIX_SIMILARITY
        if (longer.length < MIN_EDIT_LENGTH) return 0.0
        val similarity = 1.0 - levenshtein(left, right).toDouble() / longer.length
        return if (similarity >= MIN_TOKEN_SIMILARITY) similarity else 0.0
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun features(value: String): ProductFeatures {
        val lexical = TOKEN_REGEX.findAll(ascii(value).replace(',', '.')).map { it.value }.toList()
        val words = linkedSetOf<String>()
        val measures = linkedMapOf<MeasureKind, MutableSet<String>>()
        val numbers = linkedSetOf<String>()
        val packageCounts = linkedSetOf<String>()
        var index = 0
        while (index < lexical.size) {
            val token = lexical[index]
            if (token.firstOrNull()?.isDigit() == true) {
                val amount = decimal(token)
                val unit = lexical.getOrNull(index + 1)?.let(::canonicalWord)
                val spec = unit?.let(UNIT_SPECS::get)
                if (amount != null && spec != null) {
                    measures.getOrPut(spec.kind, ::linkedSetOf) += canonicalNumber(amount * spec.multiplier)
                    index += 2
                    continue
                }
                if (amount != null) numbers += canonicalNumber(amount)
                index++
                continue
            }

            val word = canonicalWord(token)
            val packageEnd = packageCountEnd(lexical, index)
            if (word in PACKAGE_MARKERS && packageEnd != null) {
                val amount = decimal(lexical[packageEnd])
                if (amount != null) packageCounts += canonicalNumber(amount)
                index = packageEnd + 1
                continue
            }
            if (word == "C" && lexical.getOrNull(index + 1) == "/") {
                val amount = lexical.getOrNull(index + 2)?.let(::decimal)
                if (amount != null) {
                    val followingUnit = lexical.getOrNull(index + 3)
                        ?.let(::canonicalWord)
                        ?.let(UNIT_SPECS::get)
                    if (followingUnit?.kind == MeasureKind.COUNT) {
                        measures.getOrPut(MeasureKind.COUNT, ::linkedSetOf) += canonicalNumber(amount)
                        index += 4
                    } else {
                        packageCounts += canonicalNumber(amount)
                        index += 3
                    }
                    continue
                }
            }
            if (word !in STOP_WORDS && word !in UNIT_SPECS && word.length >= 2) words += word
            index++
        }
        return ProductFeatures(words, measures.mapValues { it.value.toSet() }, numbers, packageCounts)
    }

    private fun packageCountEnd(tokens: List<String>, markerIndex: Int): Int? {
        var index = markerIndex + 1
        if (tokens.getOrNull(index)?.let(::canonicalWord) in setOf("C", "COM")) index++
        if (tokens.getOrNull(index) == "/" || tokens.getOrNull(index) == "X") index++
        return index.takeIf { tokens.getOrNull(it)?.firstOrNull()?.isDigit() == true }
    }

    private fun canonicalWord(value: String): String {
        val word = value.uppercase()
        WORD_ALIASES[word]?.let { return it }
        PREFIX_ALIASES.firstOrNull { (full, minimum) ->
            word.length >= minimum && full.startsWith(word)
        }?.let { return it.first }
        return word
    }

    private fun decimal(value: String): BigDecimal? = value.replace(',', '.').toBigDecimalOrNull()

    private fun canonicalNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun wordWeight(word: String): Double = if (word in GENERIC_WORDS) GENERIC_WORD_WEIGHT else WORD_WEIGHT

    private fun ascii(value: String): String = Normalizer
        .normalize(value.uppercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    private val TOKEN_REGEX = Regex("[0-9]+(?:[.,][0-9]+)?|[A-Z]+|[/]")
    private val WORD_ALIASES = mapOf(
        "ACHOC" to "ACHOCOLATADO",
        "ACHOCOL" to "ACHOCOLATADO",
        "ACHOCOLAT" to "ACHOCOLATADO",
        "BISC" to "BISCOITO",
        "DESC" to "DESCARTAVEL",
        "DESCART" to "DESCARTAVEL",
        "LIQ" to "LIQUIDO",
        "REFRI" to "REFRIGERANTE",
        "REFRIG" to "REFRIGERANTE",
        "SAQ" to "SACHE",
        "SAQS" to "SACHE",
        "SAQUINHO" to "SACHE",
        "SAQUINHOS" to "SACHE",
        "SACHES" to "SACHE",
        "TRAD" to "TRADICIONAL",
        "PCT" to "PACOTE",
        "PCTS" to "PACOTE",
        "UN" to "UNIDADE",
        "UND" to "UNIDADE",
        "UNDS" to "UNIDADE",
        "UNID" to "UNIDADE",
        "UNIDADES" to "UNIDADE",
        "GR" to "G",
        "GRS" to "G",
        "GRAMA" to "G",
        "GRAMAS" to "G",
        "KGS" to "KG",
        "QUILO" to "KG",
        "QUILOS" to "KG",
        "LT" to "L",
        "LTS" to "L",
        "LITRO" to "L",
        "LITROS" to "L"
    )
    private val PREFIX_ALIASES = listOf(
        "ACHOCOLATADO" to 5,
        "BISCOITO" to 5,
        "DESCARTAVEL" to 4,
        "INSTANTANEO" to 5,
        "REFRIGERANTE" to 5,
        "TRADICIONAL" to 4
    )
    private val UNIT_SPECS = mapOf(
        "MG" to UnitSpec(MeasureKind.WEIGHT_G, BigDecimal("0.001")),
        "G" to UnitSpec(MeasureKind.WEIGHT_G, BigDecimal.ONE),
        "KG" to UnitSpec(MeasureKind.WEIGHT_G, BigDecimal("1000")),
        "ML" to UnitSpec(MeasureKind.VOLUME_ML, BigDecimal.ONE),
        "L" to UnitSpec(MeasureKind.VOLUME_ML, BigDecimal("1000")),
        "V" to UnitSpec(MeasureKind.VOLTAGE_V, BigDecimal.ONE),
        "VOLT" to UnitSpec(MeasureKind.VOLTAGE_V, BigDecimal.ONE),
        "VOLTS" to UnitSpec(MeasureKind.VOLTAGE_V, BigDecimal.ONE),
        "W" to UnitSpec(MeasureKind.POWER_W, BigDecimal.ONE),
        "WATT" to UnitSpec(MeasureKind.POWER_W, BigDecimal.ONE),
        "WATTS" to UnitSpec(MeasureKind.POWER_W, BigDecimal.ONE),
        "MM" to UnitSpec(MeasureKind.LENGTH_MM, BigDecimal.ONE),
        "CM" to UnitSpec(MeasureKind.LENGTH_MM, BigDecimal("10")),
        "M" to UnitSpec(MeasureKind.LENGTH_MM, BigDecimal("1000")),
        "UNIDADE" to UnitSpec(MeasureKind.COUNT, BigDecimal.ONE),
        "SACHE" to UnitSpec(MeasureKind.COUNT, BigDecimal.ONE)
    )
    private val PACKAGE_MARKERS = setOf("CX", "CAIXA", "PACOTE", "PACK")
    private val STOP_WORDS = setOf("A", "AS", "C", "COM", "DA", "DAS", "DE", "DO", "DOS", "E", "EM", "PARA", "SEM")
    private val GENERIC_WORDS = setOf(
        "ACHOCOLATADO", "AGUA", "BEBIDA", "BISCOITO", "CAIXA", "CHA", "CHOCOLATE", "COPO",
        "CREME", "DESCARTAVEL", "FARINHA", "LEITE", "LIQUIDO", "PACOTE", "PO", "REFRIGERANTE",
        "SABONETE", "SACHE", "SUCO", "UNIDADE"
    )

    private const val WORD_WEIGHT = 1.20
    private const val GENERIC_WORD_WEIGHT = 0.80
    private const val MEASURE_WEIGHT = 1.50
    private const val NUMBER_WEIGHT = 1.00
    private const val PACKAGE_WEIGHT = 1.30
    private const val MIN_SCORE = 0.78
    private const val MIN_MARGIN = 0.10
    private const val SAME_PRODUCT_SCORE = 0.90
    private const val MIN_TOKEN_SIMILARITY = 0.84
    private const val PREFIX_SIMILARITY = 0.93
    private const val MIN_PREFIX_LENGTH = 4
    private const val MIN_PREFIX_RATIO = 0.45
    private const val MIN_EDIT_LENGTH = 5
    private const val SALES_UNIT_CJ = "CJ"
}

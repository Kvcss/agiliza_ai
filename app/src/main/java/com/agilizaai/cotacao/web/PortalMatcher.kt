package com.agilizaai.cotacao.web

import com.agilizaai.cotacao.data.QuoteItem

/**
 * Liga uma linha do portal a um produto da base capturada no ION.
 *
 * O código de barras é o sinal mais forte, mas a lista do ION corta EANs longos
 * ("006192058019…"), então um prefixo sozinho não é confiável: dois expositores da
 * mesma marca podem começar igual. Por isso o casamento é feito em camadas e um
 * prefixo só é aceito quando é único **e** o nome do produto confirma. Qualquer
 * dúvida deixa o campo em branco em vez de gravar preço no produto errado.
 */
object PortalMatcher {

    /** Uma linha lida da tabela do portal. */
    data class Row(
        val index: Int,
        val code: String = "",
        val barcode: String = "",
        val description: String = ""
    )

    /** Sinal que resolveu a linha, exibido no relatório final. */
    enum class Signal { BARCODE, PACKAGING_CODE, BARCODE_PREFIX, CODE, NAME }

    data class Resolution(val rowIndex: Int, val price: String, val signal: Signal)

    data class Report(
        val resolutions: List<Resolution>,
        val unmatched: List<Int> = emptyList(),
        val ambiguous: List<Int> = emptyList(),
        val conflicting: List<Int> = emptyList()
    ) {
        val matched: Int get() = resolutions.size

        fun signalCount(signal: Signal): Int = resolutions.count { it.signal == signal }
    }

    internal data class Entry(val price: String, val name: String, val barcode: String)

    class Index internal constructor(
        internal val byBarcode: Map<String, Entry>,
        internal val byPackagingCode: Map<String, Entry>,
        internal val byCode: Map<String, Entry>,
        internal val byName: Map<String, Entry>,
        internal val prefixes: List<Entry>,
        val conflictingKeys: Set<String>,
        val usableItems: Int
    ) {
        val isEmpty: Boolean get() = usableItems == 0
    }

    /**
     * Monta o índice de um prazo. Uma chave que aponta para dois preços diferentes é
     * removida: preencher qualquer um dos dois seria um chute.
     */
    fun buildIndex(items: List<QuoteItem>, conditionDays: Int): Index {
        val usable = items.mapNotNull { item ->
            val price = item.priceForWebsite(conditionDays)
            if (price.isBlank()) return@mapNotNull null
            Entry(
                price = price,
                name = normalizeName(item.description),
                barcode = item.barcode.filter(Char::isDigit)
            ) to item
        }

        val conflicts = linkedSetOf<String>()
        val byBarcode = uniqueBy(usable, conflicts, "ean") { (entry, _) ->
            entry.barcode.takeIf { it.length in FULL_EAN_LENGTHS }
        }
        // Regra do portal: no código capturado pelo app saem o primeiro e o último
        // dígito. O código exibido pelo site é normalizado separadamente no resolve.
        val byPackagingCode = uniqueBy(usable, conflicts, "emb") { (entry, _) ->
            capturedPackagingCode(entry.barcode)
        }
        val byCode = uniqueBy(usable, conflicts, "cod") { (_, item) ->
            normalizeCode(item.productCode).takeIf { it.isNotBlank() }
        }
        val byName = uniqueBy(usable, conflicts, "nome") { (entry, _) ->
            entry.name.takeIf { it.length >= MIN_NAME_LENGTH }
        }
        // Prefixos vêm dos EANs curtos demais para serem completos: são exatamente
        // os que o ION truncou na lista.
        val prefixes = usable
            .map { it.first }
            .filter { it.barcode.length in PREFIX_LENGTHS }

        return Index(
            byBarcode = byBarcode,
            byPackagingCode = byPackagingCode,
            byCode = byCode,
            byName = byName,
            prefixes = prefixes,
            conflictingKeys = conflicts,
            usableItems = usable.size
        )
    }

    fun resolve(index: Index, rows: List<Row>): Report {
        val resolutions = mutableListOf<Resolution>()
        val unmatched = mutableListOf<Int>()
        val ambiguous = mutableListOf<Int>()
        val conflicting = mutableListOf<Int>()

        rows.forEach { row ->
            val rowBarcode = row.barcode.filter(Char::isDigit)
            val rowPackagingCode = portalPackagingCode(rowBarcode)
            val rowCode = normalizeCode(row.code)
            val rowName = normalizeName(row.description)

            if (rowBarcode.isNotEmpty() && index.conflictingKeys.contains("ean:$rowBarcode")) {
                conflicting += row.index
                return@forEach
            }

            index.byBarcode[rowBarcode]?.let {
                resolutions += Resolution(row.index, it.price, Signal.BARCODE)
                return@forEach
            }

            if (rowPackagingCode != null && index.conflictingKeys.contains("emb:$rowPackagingCode")) {
                conflicting += row.index
                return@forEach
            }

            index.byPackagingCode[rowPackagingCode]?.let {
                resolutions += Resolution(row.index, it.price, Signal.PACKAGING_CODE)
                return@forEach
            }

            if (rowBarcode.length in FULL_EAN_LENGTHS) {
                val candidates = index.prefixes.filter { rowBarcode.startsWith(it.barcode) }
                when {
                    candidates.size == 1 && namesAgree(candidates.single().name, rowName) -> {
                        resolutions += Resolution(row.index, candidates.single().price, Signal.BARCODE_PREFIX)
                        return@forEach
                    }
                    candidates.size > 1 -> {
                        // Vários produtos começam com o mesmo prefixo: o nome tem de
                        // desempatar sozinho, senão a linha fica em branco.
                        val confirmed = candidates.filter { namesAgree(it.name, rowName) }
                        val prices = confirmed.map { it.price }.distinct()
                        if (confirmed.size == 1 || prices.size == 1 && confirmed.isNotEmpty()) {
                            resolutions += Resolution(row.index, confirmed.first().price, Signal.BARCODE_PREFIX)
                        } else {
                            ambiguous += row.index
                        }
                        return@forEach
                    }
                }
            }

            index.byCode[rowCode]?.takeIf { rowCode.isNotBlank() }?.let {
                // Um código igual com nome claramente diferente é coincidência de
                // numeração entre sistemas, não o mesmo produto.
                if (rowName.isBlank() || it.name.isBlank() || namesAgree(it.name, rowName)) {
                    resolutions += Resolution(row.index, it.price, Signal.CODE)
                } else {
                    ambiguous += row.index
                }
                return@forEach
            }

            index.byName[rowName]?.takeIf { rowName.length >= MIN_NAME_LENGTH }?.let {
                resolutions += Resolution(row.index, it.price, Signal.NAME)
                return@forEach
            }

            unmatched += row.index
        }

        return Report(resolutions, unmatched, ambiguous, conflicting)
    }

    private fun uniqueBy(
        source: List<Pair<Entry, QuoteItem>>,
        conflicts: MutableSet<String>,
        namespace: String,
        key: (Pair<Entry, QuoteItem>) -> String?
    ): Map<String, Entry> {
        val grouped = linkedMapOf<String, MutableList<Entry>>()
        source.forEach { candidate ->
            val resolved = key(candidate) ?: return@forEach
            grouped.getOrPut(resolved) { mutableListOf() } += candidate.first
        }
        val result = linkedMapOf<String, Entry>()
        grouped.forEach { (resolved, entries) ->
            val prices = entries.map { it.price }.distinct()
            if (prices.size == 1) {
                result[resolved] = entries.first()
            } else {
                conflicts += "$namespace:$resolved"
            }
        }
        return result
    }

    /**
     * Confirmação de nome deliberadamente tolerante a abreviações do portal
     * ("EXPOSITOR HAVAIANAS 10 GANCHOS" vs "*EXPOSITOR HAVAIANAS 10 GANCHOS(120 PARES)"),
     * mas exigente o bastante para separar dois produtos diferentes.
     */
    internal fun namesAgree(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        if (first == second) return true
        val shorter = if (first.length <= second.length) first else second
        val longer = if (first.length > second.length) first else second
        if (shorter.length >= MIN_NAME_LENGTH && longer.startsWith(shorter)) return true

        val firstTokens = first.split(' ').filter { it.isNotBlank() }.toSet()
        val secondTokens = second.split(' ').filter { it.isNotBlank() }.toSet()
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return false
        val shared = firstTokens.intersect(secondTokens).size
        val union = firstTokens.union(secondTokens).size
        return shared.toDouble() / union >= MIN_TOKEN_OVERLAP
    }

    /** O ION mostra "115.967" e o portal "051435": só os dígitos são comparáveis. */
    internal fun normalizeCode(value: String): String =
        value.filter(Char::isDigit).trimStart('0')

    /** No código capturado pelo app, remove exatamente o primeiro e o último dígito. */
    internal fun capturedPackagingCode(value: String): String? {
        val digits = value.filter(Char::isDigit)
        return digits.takeIf { it.length >= 3 }
            ?.substring(1, digits.lastIndex)
            ?.takeIf { it.isNotBlank() }
    }

    /** No Cód. Emb. lido do site, remove exatamente o último dígito. */
    internal fun portalPackagingCode(value: String): String? {
        val digits = value.filter(Char::isDigit)
        return digits.takeIf { it.length >= 2 }
            ?.dropLast(1)
            ?.takeIf { it.isNotBlank() }
    }

    internal fun normalizeName(value: String): String = java.text.Normalizer
        .normalize(value.uppercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val FULL_EAN_LENGTHS = 12..14
    private val PREFIX_LENGTHS = 8..13
    private const val MIN_NAME_LENGTH = 10
    private const val MIN_TOKEN_OVERLAP = 0.62
}

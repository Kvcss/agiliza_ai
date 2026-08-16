package com.agilizaai.cotacao.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class QuoteRepository(context: Context) {
    private val dataFile = File(context.filesDir, "quote_items.json")
    private val draftFile = File(context.filesDir, "quote_items_draft.json")
    private val atomicFile = AtomicFile(dataFile)
    private val atomicDraftFile = AtomicFile(draftFile)

    fun load(): List<QuoteItem> {
        synchronized(PROCESS_LOCK) {
            cachedSnapshot()?.let { return it.items }
            val draft = nonEmptyDraftOrNull(read(draftFile, atomicDraftFile))
            val items = draft ?: read(dataFile, atomicFile) ?: emptyList()
            cache = Snapshot(items, draft != null, stamp())
            return items
        }
    }

    /**
     * Estatísticas da base em uma única passagem. Com 3.000 produtos, contar preço
     * por prazo quatro vezes na thread principal era o que travava a abertura do app.
     */
    fun stats(): QuoteStats {
        synchronized(PROCESS_LOCK) {
            val items = load()
            val hasDraft = cache?.fromDraft == true
            val perTerm = linkedMapOf<Int, Int>()
            SUPPORTED_DAYS.forEach { perTerm[it] = 0 }
            var withPortalBarcode = 0
            items.forEach { item ->
                SUPPORTED_DAYS.forEach { days ->
                    if (item.priceForDays(days).isNotBlank()) perTerm[days] = perTerm.getValue(days) + 1
                }
                if (item.barcode.length in 8..14) withPortalBarcode++
            }
            return QuoteStats(
                total = items.size,
                pricedByDays = perTerm,
                withPortalBarcode = withPortalBarcode,
                hasDraft = hasDraft
            )
        }
    }

    fun hasDraft(): Boolean = synchronized(PROCESS_LOCK) {
        cachedSnapshot()?.let { return it.fromDraft }
        load()
        cache?.fromDraft == true
    }

    /** Assinatura barata dos arquivos: evita reler e reparsear um JSON grande sem necessidade. */
    private fun stamp(): String = buildString {
        append(if (draftFile.exists()) "${draftFile.lastModified()}:${draftFile.length()}" else "-")
        append('|')
        append(if (dataFile.exists()) "${dataFile.lastModified()}:${dataFile.length()}" else "-")
    }

    private fun cachedSnapshot(): Snapshot? = cache?.takeIf { it.stamp == stamp() }

    private fun invalidate() {
        cache = null
    }

    private data class Snapshot(val items: List<QuoteItem>, val fromDraft: Boolean, val stamp: String)

    /**
     * Starts a new protected collection. The previous draft becomes the last
     * saved collection before the working slot is cleared.
     */
    fun beginNewCapture() {
        synchronized(PROCESS_LOCK) {
            val previousDraft = read(draftFile, atomicDraftFile)
            if (!previousDraft.isNullOrEmpty()) write(atomicFile, previousDraft)
            atomicDraftFile.delete()
            invalidate()
        }
    }

    /** Atomically replaces the working collection after each product read. */
    fun replaceDraft(items: List<QuoteItem>) {
        synchronized(PROCESS_LOCK) {
            val protectedItems = eligible(items)
            if (protectedItems.isEmpty()) {
                // An empty working snapshot must never shadow quote_items.json with "[]".
                // Removing only the draft makes load() fall back to the last useful base.
                atomicDraftFile.delete()
            } else {
                write(atomicDraftFile, protectedItems)
            }
            invalidate()
        }
    }

    fun replace(items: List<QuoteItem>) {
        synchronized(PROCESS_LOCK) {
            write(atomicFile, eligible(items))
            atomicDraftFile.delete()
            invalidate()
        }
    }

    fun clear() {
        synchronized(PROCESS_LOCK) {
            atomicFile.delete()
            atomicDraftFile.delete()
            invalidate()
        }
    }

    private fun read(file: File, source: AtomicFile): List<QuoteItem>? {
        if (!file.exists()) return null
        return runCatching {
            val payload = source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toQuoteItem())
            }.let(::eligible)
        }.getOrNull()
    }

    private fun write(destination: AtomicFile, items: List<QuoteItem>) {
        val array = JSONArray()
        // rawCells guarda o texto bruto de cada nó lido no ION. Sem o corte, uma base de
        // 3.000 produtos passa de dezenas de MB e a leitura trava a abertura do aplicativo.
        items.forEach { array.put(it.withTrimmedRawCells().toJson()) }
        val stream = destination.startWrite()
        try {
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(array.toString())
            writer.flush()
            destination.finishWrite(stream)
        } catch (error: Throwable) {
            destination.failWrite(stream)
            throw error
        }
    }

    private fun QuoteItem.toJson() = JSONObject().apply {
        put("id", id)
        put("capturedAt", capturedAt)
        put("conditionDays", conditionDays)
        put("sourcePackage", sourcePackage)
        put("productCode", productCode)
        put("barcode", barcode)
        put("description", description)
        put("packaging", packaging)
        put("quantity", quantity)
        put("unitPrice", unitPrice)
        put("unitsPerCase", unitsPerCase)
        put("stock", stock)
        put("salesUnit", salesUnit)
        put("pricesByDays", JSONObject().apply {
            pricesByDays.toSortedMap().forEach { (days, price) -> put(days.toString(), price) }
        })
        put("casePricesByDays", JSONObject().apply {
            casePricesByDays.toSortedMap().forEach { (days, price) -> put(days.toString(), price) }
        })
        put("rawCells", JSONArray(rawCells))
    }

    private fun JSONObject.toQuoteItem(): QuoteItem {
        val cells = optJSONArray("rawCells") ?: JSONArray()
        val rawCells = buildList {
            for (index in 0 until cells.length()) add(cells.optString(index))
        }
        val legacyDays = optInt("conditionDays", 7)
        val legacyPrice = optString("unitPrice")
        val unitsPerCase = optString("unitsPerCase", optString("quantity"))
        val storedPrices = optJSONObject("pricesByDays").toDayPrices().toMutableMap()
        if (storedPrices.isEmpty() && legacyPrice.isNotBlank()) {
            storedPrices[legacyDays] = legacyPrice
        }
        val storedCasePricesObject = optJSONObject("casePricesByDays")
        val hasDedicatedCasePrices = storedCasePricesObject != null
        val casePrices = if (hasDedicatedCasePrices) {
            storedCasePricesObject.toDayPrices()
        } else {
            // v0.6.1 named the detail's Pço Tabela values as pricesByDays. They are
            // authoritative case/package prices and must never be multiplied again.
            storedPrices
        }
        val unitPrices = if (hasDedicatedCasePrices) {
            storedPrices.ifEmpty { deriveUnitPricesFromCases(casePrices, unitsPerCase) }
        } else {
            deriveUnitPricesFromCases(casePrices, unitsPerCase)
        }
        val primaryDays = if (legacyDays in casePrices || legacyDays in unitPrices) {
            legacyDays
        } else {
            (casePrices.keys + unitPrices.keys).minOrNull() ?: legacyDays
        }
        return QuoteItem(
            id = optString("id"),
            capturedAt = optLong("capturedAt"),
            conditionDays = legacyDays,
            sourcePackage = optString("sourcePackage"),
            productCode = optString("productCode"),
            barcode = optString("barcode"),
            description = optString("description"),
            packaging = optString("packaging"),
            quantity = optString("quantity"),
            unitPrice = unitPrices[primaryDays].orEmpty(),
            rawCells = rawCells,
            pricesByDays = unitPrices,
            unitsPerCase = unitsPerCase,
            // Captures made before the dedicated field already kept the real detail value
            // as "Estoque: 164.0" in rawCells. Migrate only that explicit labelled value.
            stock = optString("stock").ifBlank { legacyStockFromRawCells(rawCells) },
            casePricesByDays = casePrices,
            salesUnit = optString("salesUnit").ifBlank { legacySalesUnitFromRawCells(rawCells) }
        )
    }

    /**
     * Estoque ausente/ilegível é descartado, assim como um valor abaixo de três.
     * Produtos vendidos como conjunto (`Und: CJ`) também nunca entram na base.
     */
    private fun eligible(items: List<QuoteItem>): List<QuoteItem> = items.filter(QuoteItem::isEligibleForBase)

    private fun JSONObject?.toDayPrices(): Map<Int, String> {
        if (this == null) return emptyMap()
        val prices = linkedMapOf<Int, String>()
        keys().forEach { key ->
            key.toIntOrNull()?.let { days ->
                optString(key).takeIf { it.isNotBlank() }?.let { prices[days] = it }
            }
        }
        return prices
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private val SUPPORTED_DAYS = listOf(7, 14, 21, 28)

        /**
         * O serviço de captura e a tela principal vivem no mesmo processo e leem a mesma
         * base. Um cache compartilhado, validado pela data/tamanho dos arquivos, evita
         * reparsear milhares de itens a cada `onResume`.
         */
        @Volatile
        private var cache: Snapshot? = null
    }
}

/** Resumo pré-calculado da base, para a interface não varrer a lista várias vezes. */
data class QuoteStats(
    val total: Int,
    val pricedByDays: Map<Int, Int>,
    val withPortalBarcode: Int,
    val hasDraft: Boolean
) {
    fun pricedFor(days: Int): Int = pricedByDays[days] ?: 0

    companion object {
        val EMPTY = QuoteStats(0, emptyMap(), 0, false)
    }
}

internal fun deriveUnitPricesFromCases(casePrices: Map<Int, String>, unitsPerCase: String): Map<Int, String> {
    val units = unitsPerCase.toBrazilianDecimalOrNull()?.takeIf { it > java.math.BigDecimal.ZERO }
        ?: return emptyMap()
    return casePrices.mapNotNull { (days, value) ->
        value.toBrazilianDecimalOrNull()?.let { total ->
            days to total.divide(units, 6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }
    }.toMap()
}

internal fun legacyStockFromRawCells(rawCells: List<String>): String = rawCells.asSequence()
    .mapNotNull { LEGACY_STOCK.find(it)?.groupValues?.getOrNull(1) }
    .mapNotNull { it.toBrazilianDecimalOrNull() }
    .map { it.stripTrailingZeros().toPlainString() }
    .firstOrNull()
    .orEmpty()

internal fun legacySalesUnitFromRawCells(rawCells: List<String>): String = rawCells.asSequence()
    .mapNotNull { LEGACY_SALES_UNIT.find(it)?.groupValues?.getOrNull(1) }
    .firstOrNull()
    .orEmpty()

private val LEGACY_STOCK = Regex(
    "(?:ESTOQUE|EST\\.?)\\s*:\\s*([0-9]+(?:[.,][0-9]+)*)",
    RegexOption.IGNORE_CASE
)
private val LEGACY_SALES_UNIT = Regex("UNIDADE\\s*:\\s*([A-Z]{1,8})", RegexOption.IGNORE_CASE)

/** Empty/corrupt draft data is not a valid replacement for the last protected collection. */
internal fun <T> nonEmptyDraftOrNull(items: List<T>?): List<T>? = items?.takeIf { it.isNotEmpty() }
